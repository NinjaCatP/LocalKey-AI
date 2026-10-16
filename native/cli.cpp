#include "aicore.h"
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
using clk = std::chrono::steady_clock;
static double ms(clk::time_point a) { return std::chrono::duration<double, std::milli>(clk::now() - a).count(); }
int main(int argc, char** argv) {
    if (argc < 3) { fprintf(stderr, "cli model offset [threads]\n"); return 1; }
    int th = argc > 3 ? atoi(argv[3]) : 4;
    auto t0 = clk::now();
    if (!aik::load(argv[1], atol(argv[2]), th, 1024)) { printf("LOAD FAILED\n"); return 2; }
    printf("load %.0f ms\n", ms(t0));
    const char* ctxs[][2] = {{"I'll see you", ""}, {"Thank you so", ""}, {"Can you send me the", ""}, {"What time does the meeting", ""},
        {"I'm going to the", "st"}, {"Happy birthday! I hope you have a", ""}, {"Let me know if you", ""}, {"I love", "y"}, {"", ""}};
    for (auto& c : ctxs) { t0 = clk::now(); auto v = aik::predict(c[0], c[1], 3);
        printf("predict [%s|%s] ->", c[0], c[1]); for (auto& w : v) printf(" '%s'", w.c_str()); printf("   (%.0f ms)\n", ms(t0)); }
    const char* sys = "You fix grammar, spelling and punctuation. Reply with only the corrected text, nothing else.";
    const char* tests[] = {"i dont think their going to come tomorow becuase they was busy", "me and him goes to the store yesterday and buyed some apple"};
    for (auto s : tests) { t0 = clk::now(); auto r = aik::generate(sys, s, 96); printf("FIX: %s\n  -> %s   (%.0f ms)\n", s, r.c_str(), ms(t0)); }
    t0 = clk::now(); auto r = aik::generate("Rewrite the user's message to be more polite. Reply with only the rewritten message.", "send me the report now", 96);
    printf("POLITE -> %s (%.0f ms)\n", r.c_str(), ms(t0));
    t0 = clk::now(); r = aik::generate("Rewrite the user's message to be shorter and concise. Reply with only the rewritten message.", "Hey, I just wanted to reach out and let you know that I am probably going to be running a little bit late to the meeting this afternoon because of traffic.", 96);
    printf("SHORTER -> %s (%.0f ms)\n", r.c_str(), ms(t0));
    return 0;
}
