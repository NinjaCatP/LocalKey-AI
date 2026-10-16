#include "aicore.h"
#include <cstdio>
int main(int c, char** v) {
  aik::load(v[1], -1, 6, 1024);
  const char* sys[] = {
   "You correct grammar, spelling, capitalization and punctuation. Keep the meaning, wording and tone. Reply with only the corrected text, nothing else.",
   "You are a careful proofreader. Correct every grammar, spelling, capitalization and punctuation mistake in the user's text, including wrong pronouns (\"me and him\" -> \"he and I\"), verb tenses and agreement. Keep the meaning and tone and change as little as possible. Reply with only the corrected text."};
  const char* t[] = {"me and him goes to the store yesterday", "i dont think their going to come tomorow becuase they was busy", "she dont like it when peoples is late", "can u tell me where is the station", "Its been a long day and im realy tired lol", "We was hoping you could of came to the party"};
  for (auto s : sys) { printf("== %.40s\n", s); for (auto x : t) printf("  %s\n   -> %s\n", x, aik::generate(s, x, 80).c_str()); }
}
