# Romanization dictionaries

Packs downloaded on demand by the app (Lyrics menu → Romanization dictionaries). Each pack is a
gzip-compressed, tab-separated word list; the format is described in
`music/.../managers/RomanizationDictionary.kt`.

| Pack | Source | License |
|------|--------|---------|
| `ja.tsv.gz` | Words, readings and costs extracted from IPAdic 2.7.0 (via the `ipadic` Python package) | IPAdic licence, see `IPADIC-COPYING.txt` |
| `zh.tsv.gz` | Phrase and character pinyin from `pypinyin` (`phrase-pinyin-data`, `pinyin-data`), tone marks removed | MIT |

To add a language: publish another `<id>.tsv.gz` here and add a `Pack` entry to
`RomanizationDictionaries.PACKS`.
