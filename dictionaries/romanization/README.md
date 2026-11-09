# Romanization dictionaries

Packs downloaded on demand by the app (Lyrics menu → Romanization dictionaries). Each pack is a
gzip-compressed, tab-separated word list; the format is described in
`music/.../managers/RomanizationDictionary.kt`.

| Pack | Source | License |
|------|--------|---------|
| `ja.tsv.gz` | Words, readings and costs extracted from IPAdic 2.7.0 (via the `ipadic` Python package) | IPAdic licence, see `IPADIC-COPYING.txt` |
| `zh.tsv.gz` | Phrase and character pinyin from `pypinyin` (`phrase-pinyin-data`, `pinyin-data`), tone marks removed | MIT |
| `hi.tsv.gz` | Frequent Hindi words from `wordfreq` (CC-BY-SA 4.0), romanized by the rules in `tools/build_indic.py` (schwa deletion, nukta letters, common spellings) | CC-BY-SA 4.0 (word list), MIT (script) |
| `bn.tsv.gz` | Frequent Bengali words from `wordfreq` (CC-BY-SA 4.0), romanized by the rules in `tools/build_indic.py` (silent final vowel, য-ফলা / ব-ফলা doubling, ক্ষ / জ্ঞ) | CC-BY-SA 4.0 (word list), MIT (script) |
| `ur.tsv.gz` | Frequent Urdu words from `wordfreq` (CC-BY-SA 4.0), romanized by the rules in `tools/build_urdu.py` (short vowels reconstructed, aspirates, hand-checked common words) | CC-BY-SA 4.0 (word list), MIT (script) |

The Hindi, Bengali and Urdu readings are rule generated, not hand checked; they are far better than the built-in
letter-by-letter rules for the words they cover, and words they do not cover fall back to those rules.
Regenerate with `pip install wordfreq && python3 tools/build_indic.py .` (and `tools/build_urdu.py .`). Urdu omits short vowels in writing, so its readings are the least certain of the three; the same rules also run on-device (`UrduLatin.kt`) for unlisted words.

To add a language: publish another `<id>.tsv.gz` here and add a `Pack` entry to
`RomanizationDictionaries.PACKS`.
