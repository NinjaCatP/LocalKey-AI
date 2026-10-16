#include "aicore.h"
#include "llama.h"
#include <atomic>
#include <mutex>
#include <algorithm>
#include <cstring>
#include <cctype>
#include <cmath>

namespace aik {
static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static const llama_vocab* g_vocab = nullptr;
static std::vector<llama_token> g_cached; // tokens currently in KV seq 0
static std::atomic<int> g_cancel{0};
static std::mutex g_mu;
static int g_nctx = 1024;
static std::vector<std::string> g_piece; // cache of token pieces

static void quiet_log(ggml_log_level, const char*, void*) {}

bool loaded() { return g_ctx != nullptr; }
void cancel() { g_cancel++; }

bool load(const char* path, long offset, int threads, int nctx) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_ctx) return true;
    llama_log_set(quiet_log, nullptr);
    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    if (offset >= 0) {
        FILE* f = fopen(path, "rb");
        if (!f) return false;
        if (fseek(f, offset, SEEK_SET) != 0) { fclose(f); return false; }
        g_model = llama_model_load_from_file_ptr(f, mp);
        // the loader keeps its own handle / mapping; keep f open for lifetime (small leak is fine)
    } else {
        g_model = llama_model_load_from_file(path, mp);
    }
    if (!g_model) return false;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = nctx; cp.n_batch = 256; cp.n_ubatch = 256; cp.n_seq_max = 1;
    cp.n_threads = threads; cp.n_threads_batch = threads;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { llama_model_free(g_model); g_model = nullptr; return false; }
    g_nctx = nctx;
    g_vocab = llama_model_get_vocab(g_model);
    int n = llama_vocab_n_tokens(g_vocab);
    g_piece.resize(n);
    char buf[256];
    for (int i = 0; i < n; i++) {
        int l = llama_token_to_piece(g_vocab, i, buf, sizeof buf, 0, false);
        g_piece[i] = l > 0 ? std::string(buf, l) : std::string();
    }
    g_cached.clear();
    return true;
}

void unload() {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_ctx) llama_free(g_ctx);
    if (g_model) llama_model_free(g_model);
    g_ctx = nullptr; g_model = nullptr; g_cached.clear();
}

static std::vector<llama_token> tokenize(const std::string& s, bool special) {
    std::vector<llama_token> t(s.size() + 16);
    int n = llama_tokenize(g_vocab, s.data(), (int)s.size(), t.data(), (int)t.size(), false, special);
    if (n < 0) { t.resize(-n); n = llama_tokenize(g_vocab, s.data(), (int)s.size(), t.data(), (int)t.size(), false, special); }
    t.resize(std::max(n, 0));
    return t;
}

// decode tokens[from..] appending to KV seq 0 at positions from..; logits for last
static bool decode_from(const std::vector<llama_token>& toks, size_t from) {
    const int B = 256;
    llama_batch b = llama_batch_init(B, 0, 1);
    bool ok = true;
    int startCancel = g_cancel.load();
    for (size_t i = from; i < toks.size() && ok; i += B) {
        if (g_cancel.load() != startCancel) { ok = false; break; }
        b.n_tokens = 0;
        size_t end = std::min(toks.size(), i + B);
        for (size_t j = i; j < end; j++) {
            b.token[b.n_tokens] = toks[j]; b.pos[b.n_tokens] = (llama_pos)j;
            b.n_seq_id[b.n_tokens] = 1; b.seq_id[b.n_tokens][0] = 0;
            b.logits[b.n_tokens] = (j == toks.size() - 1);
            b.n_tokens++;
        }
        if (llama_decode(g_ctx, b) != 0) ok = false;
    }
    llama_batch_free(b);
    return ok;
}

// make KV seq 0 hold exactly `toks`, reusing the common prefix; logits of last token available
static bool sync_prompt(std::vector<llama_token> toks) {
    if (toks.empty()) return false;
    llama_memory_t mem = llama_get_memory(g_ctx);
    size_t common = 0;
    while (common < g_cached.size() && common < toks.size() && g_cached[common] == toks[common]) common++;
    if (common == toks.size()) common--; // need to recompute last token for logits
    if (!llama_memory_seq_rm(mem, 0, (llama_pos)common, -1)) { llama_memory_clear(mem, true); common = 0; }
    g_cached.resize(common);
    if (!decode_from(toks, common)) { llama_memory_seq_rm(mem, 0, -1, -1); g_cached.clear(); return false; }
    g_cached = toks;
    return true;
}

static llama_token argmax(const float* lg, int n) {
    int best = 0; for (int i = 1; i < n; i++) if (lg[i] > lg[best]) best = i; return best;
}

static bool is_word_char(unsigned char c) { return std::isalnum(c) || c == '\''; }

static std::string lower(std::string s) { for (auto& c : s) c = (char)std::tolower((unsigned char)c); return s; }

std::vector<std::string> predict(const std::string& context, const std::string& prefix, int k) {
    std::lock_guard<std::mutex> lk(g_mu);
    std::vector<std::string> out;
    if (!g_ctx) return out;
    int myCancel = g_cancel.load();
    // keep only the tail of the context
    std::string ctx = context;
    if (ctx.size() > 600) ctx = ctx.substr(ctx.size() - 600);
    // strip trailing spaces: the word candidate tokens carry the leading space (BPE style)
    bool needSpace = true;
    while (!ctx.empty() && ctx.back() == ' ') ctx.pop_back();
    if (ctx.empty()) { ctx = "\n"; needSpace = false; }
    else if (ctx.back() == '\n') needSpace = false;
    std::vector<llama_token> toks = tokenize(ctx, false);
    if ((int)toks.size() > g_nctx - 16) toks.erase(toks.begin(), toks.end() - (g_nctx - 16));
    if (!sync_prompt(toks)) return out;
    const int nv = llama_vocab_n_tokens(g_vocab);
    std::vector<float> lg(llama_get_logits_ith(g_ctx, -1), llama_get_logits_ith(g_ctx, -1) + nv);
    std::string lp = lower(prefix);
    // candidate first tokens
    std::vector<std::pair<float,int>> cand;
    for (int i = 0; i < nv; i++) {
        const std::string& p = g_piece[i];
        if (p.empty()) continue;
        std::string w = p;
        if (needSpace) { if (w[0] != ' ') continue; w = w.substr(1); }
        else if (w[0] == ' ') w = w.substr(1);
        if (w.empty() || !is_word_char((unsigned char)w[0])) continue;
        bool allw = true; for (unsigned char c : w) if (!is_word_char(c)) { allw = false; break; }
        if (!allw) continue;
        std::string lw = lower(w);
        size_t m = std::min(lw.size(), lp.size());
        if (lw.compare(0, m, lp, 0, m) != 0) continue;
        cand.push_back({lg[i], i});
    }
    int take = std::min<int>((int)cand.size(), std::max(k * 3, 8));
    std::partial_sort(cand.begin(), cand.begin() + take, cand.end(), [](auto& a, auto& b){ return a.first > b.first; });
    cand.resize(take);
    llama_memory_t mem = llama_get_memory(g_ctx);
    const llama_pos base = (llama_pos)toks.size();
    llama_batch b = llama_batch_init(1, 0, 1);
    for (auto& c : cand) {
        if ((int)out.size() >= k || g_cancel.load() != myCancel) break;
        std::string word = g_piece[c.second];
        if (!word.empty() && word[0] == ' ') word = word.substr(1);
        llama_token t = c.second; llama_pos pos = base;
        // greedily extend while the next token continues the word (no leading space)
        for (int step = 0; step < 4; step++) {
            b.n_tokens = 1; b.token[0] = t; b.pos[0] = pos++; b.n_seq_id[0] = 1; b.seq_id[0][0] = 0; b.logits[0] = 1;
            if (llama_decode(g_ctx, b) != 0) break;
            const float* l2 = llama_get_logits_ith(g_ctx, -1);
            // best continuation consistent with prefix, versus best word-ending token
            llama_token best = argmax(l2, nv);
            const std::string& np = g_piece[best];
            if (np.empty() || np[0] == ' ' || !is_word_char((unsigned char)np[0])) {
                // word finished; but if word still shorter than prefix, try best continuation matching prefix
                if (lower(word).size() >= lp.size()) break;
            }
            // pick best continuation token that keeps prefix consistency
            llama_token pick = -1; float pv = -INFINITY;
            for (int i = 0; i < nv; i++) {
                const std::string& q = g_piece[i];
                if (q.empty() || q[0] == ' ' || !is_word_char((unsigned char)q[0])) continue;
                bool allw = true; for (unsigned char ch : q) if (!is_word_char(ch)) { allw = false; break; }
                if (!allw) continue;
                std::string nw = lower(word + q);
                size_t m = std::min(nw.size(), lp.size());
                if (nw.compare(0, m, lp, 0, m) != 0) continue;
                if (l2[i] > pv) { pv = l2[i]; pick = i; }
            }
            if (pick < 0) break;
            if (lower(word).size() >= lp.size() && pick != best) break;
            word += g_piece[pick]; t = pick;
        }
        llama_memory_seq_rm(mem, 0, base, -1);
        if (lower(word).size() < lp.size() || lower(word).compare(0, lp.size(), lp) != 0) continue;
        bool dup = false; for (auto& o : out) if (lower(o) == lower(word)) dup = true;
        if (!dup && !word.empty()) out.push_back(word);
    }
    llama_batch_free(b);
    g_cached.resize(base);
    return out;
}

std::string generate(const std::string& system, const std::string& user, int maxTokens) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (!g_ctx) return "";
    int myCancel = g_cancel.load();
    llama_chat_message msgs[2] = {{"system", system.c_str()}, {"user", user.c_str()}};
    const char* tmpl = llama_model_chat_template(g_model, nullptr);
    std::vector<char> buf(system.size() * 2 + user.size() * 2 + 512);
    int n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int)buf.size());
    if (n > (int)buf.size()) { buf.resize(n + 1); n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int)buf.size()); }
    if (n < 0) return "";
    std::vector<llama_token> toks = tokenize(std::string(buf.data(), n), true);
    if ((int)toks.size() + maxTokens > g_nctx) return "";
    if (!sync_prompt(toks)) return "";
    const int nv = llama_vocab_n_tokens(g_vocab);
    std::string out;
    llama_batch b = llama_batch_init(1, 0, 1);
    llama_pos pos = (llama_pos)toks.size();
    std::vector<llama_token> gen;
    for (int i = 0; i < maxTokens; i++) {
        if (g_cancel.load() != myCancel) { out.clear(); break; }
        const float* lg = llama_get_logits_ith(g_ctx, -1);
        std::vector<float> l(lg, lg + nv);
        // mild repetition penalty
        for (size_t j = gen.size() > 32 ? gen.size() - 32 : 0; j < gen.size(); j++) { float& v = l[gen[j]]; v = v > 0 ? v / 1.1f : v * 1.1f; }
        llama_token t = argmax(l.data(), nv);
        if (llama_vocab_is_eog(g_vocab, t)) break;
        out += g_piece[t]; gen.push_back(t);
        b.n_tokens = 1; b.token[0] = t; b.pos[0] = pos++; b.n_seq_id[0] = 1; b.seq_id[0][0] = 0; b.logits[0] = 1;
        if (llama_decode(g_ctx, b) != 0) break;
    }
    llama_batch_free(b);
    // drop generated tokens from cache so the prompt prefix stays reusable
    llama_memory_seq_rm(llama_get_memory(g_ctx), 0, (llama_pos)toks.size(), -1);
    g_cached = toks;
    // trim
    while (!out.empty() && std::isspace((unsigned char)out.back())) out.pop_back();
    size_t s = 0; while (s < out.size() && std::isspace((unsigned char)out[s])) s++;
    return out.substr(s);
}
}
