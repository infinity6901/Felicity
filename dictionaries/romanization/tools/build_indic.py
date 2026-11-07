#!/usr/bin/env python3
"""Builds the Hindi (hi.tsv.gz) and Bengali (bn.tsv.gz) romanization packs.

Words come from the `wordfreq` package (frequency lists, CC-BY-SA 4.0); the readings are produced by
the rules below: pronunciation-aware romanization with schwa deletion for Hindi and the final-vowel /
conjunct rules for Bengali. Run:  pip install wordfreq && python3 build_indic.py <output-dir>
"""
import gzip
import sys
import unicodedata

from wordfreq import top_n_list

HEADER = "#felicity-romanization-dictionary"

# ---------------------------------------------------------------- Hindi

HI_CONS = {
    'क': 'k', 'ख': 'kh', 'ग': 'g', 'घ': 'gh', 'ङ': 'ng', 'च': 'ch', 'छ': 'chh', 'ज': 'j', 'झ': 'jh', 'ञ': 'n',
    'ट': 't', 'ठ': 'th', 'ड': 'd', 'ढ': 'dh', 'ण': 'n', 'त': 't', 'थ': 'th', 'द': 'd', 'ध': 'dh', 'न': 'n',
    'प': 'p', 'फ': 'ph', 'ब': 'b', 'भ': 'bh', 'म': 'm', 'य': 'y', 'र': 'r', 'ल': 'l', 'व': 'v', 'श': 'sh',
    'ष': 'sh', 'स': 's', 'ह': 'h', 'ळ': 'l',
    'क\u093c': 'q', 'ख\u093c': 'kh', 'ग\u093c': 'gh', 'ज\u093c': 'z', 'ड\u093c': 'r', 'ढ\u093c': 'rh', 'फ\u093c': 'f',
}
HI_VOWELS = {'अ': 'a', 'आ': 'aa', 'इ': 'i', 'ई': 'i', 'उ': 'u', 'ऊ': 'u', 'ऋ': 'ri', 'ए': 'e', 'ऐ': 'ai',
             'ओ': 'o', 'औ': 'au', 'ऑ': 'o'}
HI_MATRAS = {'ा': 'aa', 'ि': 'i', 'ी': 'i', 'ु': 'u', 'ू': 'u', 'ृ': 'ri', 'े': 'e', 'ै': 'ai', 'ो': 'o',
             'ौ': 'au', 'ॉ': 'o'}
HI_OVERRIDE = {
    'में': 'mein', 'मैं': 'main', 'हूँ': 'hoon', 'हूं': 'hoon', 'नहीं': 'nahi', 'यहाँ': 'yahan', 'वहाँ': 'wahan',
    'कहाँ': 'kahan', 'क्या': 'kya', 'क्यों': 'kyon', 'कुछ': 'kuch', 'सब': 'sab', 'तुम': 'tum', 'हम': 'hum',
    'ये': 'yeh', 'वो': 'woh', 'वह': 'woh', 'यह': 'yeh', 'है': 'hai', 'हैं': 'hain', 'था': 'tha', 'थी': 'thi',
    'थे': 'the', 'और': 'aur', 'का': 'ka', 'की': 'ki', 'के': 'ke', 'को': 'ko', 'से': 'se', 'पर': 'par',
    'तू': 'tu', 'तेरा': 'tera', 'तेरी': 'teri', 'मेरा': 'mera', 'मेरी': 'meri', 'मेरे': 'mere', 'तेरे': 'tere',
    'प्यार': 'pyaar', 'इश्क़': 'ishq', 'इश्क': 'ishq', 'दिल': 'dil', 'ज़िन्दगी': 'zindagi', 'ज़िंदगी': 'zindagi',
    'ख़ुशी': 'khushi', 'खुशी': 'khushi', 'दुनिया': 'duniya', 'रात': 'raat', 'दिन': 'din', 'आँखें': 'aankhein',
    'आंखें': 'aankhein', 'ख़्वाब': 'khwaab', 'ख्वाब': 'khwaab', 'सपना': 'sapna', 'सपने': 'sapne',
}


def _units(word, cons_map, vowels, matras, inherent):
    """Splits a word into syllables [onset, vowel, implicit, coda]."""
    word = unicodedata.normalize('NFC', word)
    # recompose nukta forms that NFC splits
    syl = []
    i = 0
    n = len(word)
    pending = ''  # consonants of a cluster waiting for their vowel
    while i < n:
        ch = word[i]
        # nukta forms are decomposed in NFC for some letters; join them back
        if i + 1 < n and word[i + 1] in ('\u093c', '\u09bc') and ch + word[i + 1] in cons_map:
            ch = ch + word[i + 1]
            i += 1
        if ch in cons_map:
            pending += cons_map[ch]
            nxt = word[i + 1] if i + 1 < n else ''
            if nxt == '्' or nxt == '্':  # virama joins the next consonant
                i += 2
                pending += '\u0001'
                continue
            if nxt in matras:
                syl.append([pending.replace('\u0001', ''), matras[nxt], False, '', pending.count('\u0001')])
                pending = ''
                i += 2
                continue
            syl.append([pending.replace('\u0001', ''), inherent, True, '', pending.count('\u0001')])
            pending = ''
        elif ch in vowels:
            syl.append(['', vowels[ch], False, '', 0])
        elif ch in 'ंঁँ' or ch == 'ং':
            if syl:
                syl[-1][3] += 'n' if ch != 'ং' else 'ng'
        elif ch in 'ःঃ':
            if syl:
                syl[-1][3] += 'h'
        elif ch in matras:
            pass
        i += 1
    return syl


def hindi(word):
    if word in HI_OVERRIDE:
        return HI_OVERRIDE[word]
    syl = _units(word, HI_CONS, HI_VOWELS, HI_MATRAS, 'a')
    if not syl:
        return None
    n = len(syl)
    keep = [True] * n  # does the syllable keep its vowel
    if n > 1 and syl[-1][2] and not syl[-1][3]:
        keep[-1] = False
    # right-to-left medial schwa deletion: VC _ CV
    for i in range(n - 2, 0, -1):
        if not syl[i][2] or syl[i][3]:
            continue
        # VC _ CV: both neighbours keep a vowel, and the next onset is a single consonant
        if keep[i - 1] and keep[i + 1] and syl[i + 1][4] == 0:
            keep[i] = False
    out = ''
    for (onset, vowel, implicit, coda, _), k in zip(syl, keep):
        out += onset + (vowel if k else '') + coda
    # a word-final long aa is written "a" (jaana, muskurana), as lyrics usually do
    if out.endswith('aa') and len(out) > 3 and not out.endswith('aaa'):
        out = out[:-1]
    return out


# ---------------------------------------------------------------- Bengali

BN_CONS = {
    'ক': 'k', 'খ': 'kh', 'গ': 'g', 'ঘ': 'gh', 'ঙ': 'ng', 'চ': 'ch', 'ছ': 'chh', 'জ': 'j', 'ঝ': 'jh', 'ঞ': 'n',
    'ট': 't', 'ঠ': 'th', 'ড': 'd', 'ঢ': 'dh', 'ণ': 'n', 'ত': 't', 'থ': 'th', 'দ': 'd', 'ধ': 'dh', 'ন': 'n',
    'প': 'p', 'ফ': 'ph', 'ব': 'b', 'ভ': 'bh', 'ম': 'm', 'য': 'j', 'র': 'r', 'ল': 'l', 'শ': 'sh', 'ষ': 'sh',
    'স': 's', 'হ': 'h', 'ড়': 'r', 'ঢ়': 'rh', 'য়': 'y', 'ৎ': 't', 'ড়': 'r', 'ঢ়': 'rh',
}
BN_VOWELS = {'অ': 'o', 'আ': 'a', 'ই': 'i', 'ঈ': 'i', 'উ': 'u', 'ঊ': 'u', 'ঋ': 'ri', 'এ': 'e', 'ঐ': 'oi',
             'ও': 'o', 'ঔ': 'ou'}
BN_MATRAS = {'া': 'a', 'ি': 'i', 'ী': 'i', 'ু': 'u', 'ূ': 'u', 'ৃ': 'ri', 'ে': 'e', 'ৈ': 'oi', 'ো': 'o',
             'ৌ': 'ou'}
BN_OVERRIDE = {'আমি': 'ami', 'তুমি': 'tumi', 'আমার': 'amar', 'তোমার': 'tomar', 'ভালোবাসা': 'bhalobasha',
               'ভালবাসা': 'bhalobasha', 'ভালোবাসি': 'bhalobashi', 'ভালো': 'bhalo', 'আমরা': 'amra', 'কি': 'ki',
               'কী': 'ki', 'না': 'na', 'এই': 'ei', 'ও': 'o', 'আর': 'ar', 'এবং': 'ebong'}


def bengali(word):
    if word in BN_OVERRIDE:
        return BN_OVERRIDE[word]
    w = unicodedata.normalize('NFC', word)
    keep_final = w.endswith('\u09cd\u09af') or w.endswith('\u09cd\u09ac')
    initial_ksh = w.startswith('ক্ষ')
    # য-ফলা / ব-ফলা after a consonant double that consonant (সত্য -> shotto, বিশ্ব -> bishsho);
    # at the very start of a word ব-ফলা is silent (স্বপ্ন -> shopno).
    out_chars = []
    i = 0
    while i < len(w):
        c = w[i]
        if c == '\u09cd' and i + 1 < len(w) and w[i + 1] in 'যব' and out_chars and i > 1:
            prev = out_chars[-1]
            if prev in BN_CONS or prev + (w[i - 0:i] or '') in BN_CONS:
                out_chars.append('\u09cd')
                out_chars.append(prev)
                i += 2
                continue
        out_chars.append(c)
        i += 1
    w = ''.join(out_chars)
    if initial_ksh:
        w = '\u0002' + w[3:]
    elif len(w) > 3 and w[1] == '\u09cd' and w[2] == 'ব' and w[0] in BN_CONS:
        w = w[0] + w[3:]  # initial ব-ফলা is silent
    w = w.replace('ক্ষ', 'ক্\u0002').replace('জ্ঞ', '\u0003')
    table = dict(BN_CONS)
    table['\u0002'] = 'kh' if initial_ksh else 'kkh'
    table['\u0003'] = 'gy'
    # স before a cluster consonant stays "s" (skul, stri); otherwise it is "sh"
    table['স'] = 'sh'
    syl = _units(w, table, BN_VOWELS, BN_MATRAS, 'o')
    if not syl:
        return None
    if len(syl) > 1 and syl[-1][2] and not syl[-1][3] and not keep_final:
        syl[-1][1] = ''  # final inherent vowel is silent
    out = ''.join(onset + vowel + coda for onset, vowel, implicit, coda, _ in syl)
    for a_, b_ in (('shk', 'sk'), ('sht', 'st'), ('shth', 'sth'), ('shn', 'sn'), ('shp', 'sp'), ('shl', 'sl')):
        out = out.replace(a_, b_)
    return out


def cost(rank, total):
    return 1500 + int(3500 * rank / max(1, total))


def build(lang, words, fn, out):
    rows = {}
    for rank, w in enumerate(words):
        if any(c.isdigit() or c in '.-/' for c in w) or len(w) < 1:
            continue
        r = fn(w)
        if r and r != w:
            rows.setdefault(w, (r, cost(rank, len(words))))
    with gzip.open(out, 'wt', encoding='utf-8', compresslevel=9) as f:
        f.write(f"{HEADER}\t{lang}\tsurface\treading\tpron-for-particles\tcost\tclass\n")
        for w, (r, c) in rows.items():
            f.write(f"{w}\t{r}\t\t{c}\tN\n")
    return len(rows)


if __name__ == '__main__':
    out = sys.argv[1] if len(sys.argv) > 1 else '.'
    if len(sys.argv) > 2 and sys.argv[2] == 'test':
        for w in ['कमल', 'बनता', 'समझना', 'तुम्हारी', 'प्यार', 'ज़िन्दगी', 'सपनों', 'दोस्ती', 'मोहब्बत', 'मुस्कुराना', 'आसमान', 'चाँद', 'रोशनी', 'जाना', 'पहचान']:
            print(w, hindi(w))
        for w in ['সুন্দর', 'বাংলা', 'ভালোবাসা', 'আকাশ', 'সত্য', 'বিশ্ব', 'স্বপ্ন', 'ক্ষমা', 'জ্ঞান', 'বন্ধু', 'কখনো', 'শরীর', 'হৃদয়', 'তোমাকে', 'গান']:
            print(w, bengali(w))
        sys.exit()
    print('hi', build('hi', top_n_list('hi', 40000), hindi, f'{out}/hi.tsv.gz'))
    print('bn', build('bn', top_n_list('bn', 60000), bengali, f'{out}/bn.tsv.gz'))
