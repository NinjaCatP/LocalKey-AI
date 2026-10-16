#pragma once
#include <string>
#include <vector>
#include <cstdio>
namespace aik {
bool load(const char* path, long offset, int threads, int nctx); // offset<0 => path open normally
bool loaded();
void cancel();
// next word / completion: context = text before the partial word, prefix = partial word (may be empty)
std::vector<std::string> predict(const std::string& context, const std::string& prefix, int k);
std::string generate(const std::string& system, const std::string& user, int maxTokens);
void unload();
}
