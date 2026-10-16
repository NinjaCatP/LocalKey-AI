// aikv_cli model.bin a.wav [b.wav...] -> per file: ms, audio_s, text   (16 kHz mono s16 wav)
#include <cstdio>
#include <string>
#include <vector>
#include <chrono>
extern "C" bool aikv_load(const char *, long long, long long, int);
extern "C" const char *aikv_error();
extern "C" const char *whisper_print_system_info();
extern "C" std::string aikv_transcribe(const float *, int, const char *, bool);
int main(int argc, char **argv) {
    int th = getenv("AIKV_THREADS") ? atoi(getenv("AIKV_THREADS")) : 4; bool fast = getenv("AIKV_FAST") != nullptr;
    auto t0 = std::chrono::steady_clock::now();
    long long off = getenv("AIKV_OFFSET") ? atoll(getenv("AIKV_OFFSET")) : 0, len = getenv("AIKV_LENGTH") ? atoll(getenv("AIKV_LENGTH")) : -1;
    fprintf(stderr, "system_info: %s\n", whisper_print_system_info());
    if (!aikv_load(argv[1], off, len, th)) { fprintf(stderr, "load failed: %s\n", aikv_error()); return 1; }
    printf("LOAD_MS\t%.0f\n", std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
    for (int i = 2; i < argc; i++) {
        FILE *f = fopen(argv[i], "rb"); if (!f) continue; fseek(f, 44, SEEK_SET);
        std::vector<short> s; short b[4096]; size_t r; while ((r = fread(b, 2, 4096, f)) > 0) s.insert(s.end(), b, b + r); fclose(f);
        std::vector<float> x(s.size()); for (size_t k = 0; k < s.size(); k++) x[k] = s[k] / 32768.f;
        auto t = std::chrono::steady_clock::now();
        std::string out = aikv_transcribe(x.data(), (int)x.size(), nullptr, fast);
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count();
        printf("%s\t%.0f\t%.2f\t%s\n", argv[i], ms, x.size() / 16000.0, out.c_str()); fflush(stdout);
    }
}
