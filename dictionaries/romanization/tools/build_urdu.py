#!/usr/bin/env python3
"""Builds the Urdu romanization pack (ur.tsv.gz).

Urdu is written without short vowels, so a reading has to be reconstructed. The rules here are the
same ones as music/.../managers/UrduLatin.kt (the on-device fallback for words the pack does not
list): letter values, long-vowel letters, an inherent "a" between consecutive consonants, and a
hand-checked list of the most common words (HAND) that the rules would otherwise misread.
Words come from `wordfreq` (CC-BY-SA 4.0). Run:  pip install wordfreq && python3 build_urdu.py <output-dir>
"""
import gzip
import sys
import unicodedata

from wordfreq import top_n_list

HEADER = "#felicity-romanization-dictionary"

CONS = {
    'ب': 'b', 'پ': 'p', 'ت': 't', 'ٹ': 't', 'ث': 's', 'ج': 'j', 'چ': 'ch', 'ح': 'h', 'خ': 'kh', 'د': 'd',
    'ڈ': 'd', 'ذ': 'z', 'ر': 'r', 'ڑ': 'r', 'ز': 'z', 'ژ': 'zh', 'س': 's', 'ش': 'sh', 'ص': 's', 'ض': 'z',
    'ط': 't', 'ظ': 'z', 'غ': 'gh', 'ف': 'f', 'ق': 'q', 'ک': 'k', 'ك': 'k', 'گ': 'g', 'ل': 'l', 'م': 'm',
    'ن': 'n', 'ں': 'n', 'ہ': 'h', 'ھ': 'h', 'ه': 'h', 'ۃ': 't',
}
ZABAR, ZER, PESH, SHADDA, SUKUN = 'َ', 'ِ', 'ُ', 'ّ', 'ْ'
DIACRITICS = {'ً', 'ٌ', 'ٍ', ZABAR, ZER, PESH, SHADDA, SUKUN, 'ٰ', 'ٖ', 'ٗ', 'ٔ'}

HAND = {
    'کے': 'ke', 'میں': 'mein', 'کی': 'ki', 'ہے': 'hai', 'اور': 'aur', 'سے': 'se', 'کا': 'ka', 'کو': 'ko',
    'اس': 'is', 'پر': 'par', 'ہیں': 'hain', 'نے': 'ne', 'بھی': 'bhi', 'ایک': 'ek', 'کر': 'kar', 'یہ': 'yeh',
    'نہیں': 'nahin', 'کہ': 'ke', 'تو': 'to', 'کیا': 'kya', 'وہ': 'woh', 'ہو': 'ho', 'ان': 'un', 'تھا': 'tha',
    'جو': 'jo', 'ہی': 'hi', 'و': 'o', 'جس': 'jis', 'نہ': 'na', 'گیا': 'gaya', 'آپ': 'aap', 'لیے': 'liye',
    'اللہ': 'allah', 'تھے': 'the', 'بعد': 'baad', 'کوئی': 'koi', 'اپنے': 'apne', 'گا': 'ga', 'ہم': 'hum',
    'کسی': 'kisi', 'تھی': 'thi', 'کرنے': 'karne', 'یا': 'ya', 'ساتھ': 'saath', 'جب': 'jab', 'رہے': 'rahe',
    'تک': 'tak', 'گے': 'ge', 'دیا': 'diya', 'والے': 'wale', 'بات': 'baat', 'ہوئے': 'hue', 'اگر': 'agar',
    'ہونے': 'hone', 'جائے': 'jaye', 'ہوں': 'hoon', 'اپنی': 'apni', 'ہوا': 'hua', 'کرتے': 'karte',
    'میرا': 'mera', 'میری': 'meri', 'میرے': 'mere', 'تیرا': 'tera', 'تیری': 'teri', 'تیرے': 'tere',
    'تم': 'tum', 'تمہارا': 'tumhara', 'تمہاری': 'tumhari', 'تمہارے': 'tumhare', 'مجھے': 'mujhe',
    'مجھ': 'mujh', 'تجھے': 'tujhe', 'ہمیں': 'humein', 'تمہیں': 'tumhein', 'ہمارا': 'hamara', 'ہماری': 'hamari',
    'دل': 'dil', 'پیار': 'pyaar', 'عشق': 'ishq', 'محبت': 'mohabbat', 'زندگی': 'zindagi', 'دنیا': 'duniya',
    'خواب': 'khwaab', 'آنکھیں': 'aankhein', 'آنکھ': 'aankh', 'رات': 'raat', 'دن': 'din', 'چاند': 'chaand',
    'تارے': 'taare', 'آسمان': 'aasmaan', 'یاد': 'yaad', 'یادیں': 'yaadein', 'درد': 'dard', 'غم': 'gham',
    'خوشی': 'khushi', 'ہنسی': 'hansi', 'آنسو': 'aansoo', 'جان': 'jaan', 'جاں': 'jaan', 'دوست': 'dost',
    'دوستی': 'dosti', 'وقت': 'waqt', 'سانس': 'saans', 'روح': 'rooh', 'ہمیشہ': 'hamesha', 'کبھی': 'kabhi',
    'ابھی': 'abhi', 'کہیں': 'kahin', 'کہاں': 'kahan', 'یہاں': 'yahan', 'وہاں': 'wahan', 'کیوں': 'kyun',
    'جیسے': 'jaise', 'ایسے': 'aise', 'ایسا': 'aisa', 'کچھ': 'kuch', 'سب': 'sab', 'بس': 'bas', 'پھر': 'phir',
    'اب': 'ab', 'جی': 'ji', 'ہاں': 'haan', 'اے': 'ae', 'او': 'o', 'ارے': 'arey', 'سنو': 'suno',
    'دیکھو': 'dekho', 'چلو': 'chalo', 'آؤ': 'aao', 'آؤں': 'aaoon', 'آ': 'aa', 'جا': 'ja', 'جاؤ': 'jao',
    'رہنا': 'rehna', 'رہو': 'raho', 'کہو': 'kaho', 'بولو': 'bolo', 'مجھ کو': 'mujh ko', 'تجھ': 'tujh',
    'ساتھی': 'saathi', 'سنگ': 'sang', 'صنم': 'sanam', 'دلبر': 'dilbar', 'ہجر': 'hijr', 'وصال': 'visaal',
    'شام': 'shaam', 'صبح': 'subah', 'بارش': 'baarish', 'ہوا': 'hawa', 'خدا': 'khuda', 'رب': 'rab',
    'مولا': 'maula', 'گئے': 'gaye', 'گئی': 'gayi', 'گیا': 'gaya', 'بہت': 'bohot', 'شکریہ': 'shukriya', 'خوبصورت': 'khoobsurat', 'ہندوستان': 'hindustan', 'پاکستان': 'pakistan', 'یار': 'yaar', 'سجنا': 'sajna', 'ساجن': 'saajan', 'قسم': 'qasam', 'وعدہ': 'waada',
}


def _in(ch, chars):
    return ch != '' and ch in chars


def urdu(word):
    if word in HAND:
        return HAND[word]
    w = unicodedata.normalize('NFC', word)
    letters = [c for c in w if c not in ('\u200c', '\u200d', '\u0640')]
    out = []  # [kind, text]; kind: 'C' consonant, 'V' vowel, 'X' other
    n = len(letters)
    for i, c in enumerate(letters):
        nxt = letters[i + 1] if i + 1 < n else ''
        first = not any(k != 'X' for k, _ in out)
        last = all(x in DIACRITICS for x in letters[i + 1:])
        if c in DIACRITICS:
            if c == SHADDA and out and out[-1][0] == 'C':
                out.append(['C', out[-1][1]])
            elif c == ZABAR:
                out.append(['V', 'a'])
            elif c == ZER:
                out.append(['V', 'i'])
            elif c == PESH:
                out.append(['V', 'u'])
        elif c == 'آ':
            out.append(['V', 'aa'])
        elif c == 'ا':
            out.append(['V', 'a' if (first or last) else 'aa'])
        elif c == 'ع':
            if first:
                out.append(['V', 'a'])
        elif c == 'ء':
            pass
        elif c in 'یيې':
            if _in(nxt, 'اوے') or (first and nxt):
                out.append(['C', 'y'])
            elif last:
                out.append(['V', 'i'])
            else:
                out.append(['V', 'ee'])
        elif c == 'ے':
            out.append(['V', 'e'])
        elif c == 'ؤ':
            out.append(['V', 'o'])
        elif c == 'ئ':
            if _in(nxt, 'ی'):
                out.append(['V', ''])
            else:
                out.append(['C', 'y'])
        elif c == 'و':
            if first and nxt:
                out.append(['C', 'w'])
            else:
                out.append(['V', 'o'])
        elif c == 'ہ' and last and out and out[-1][0] == 'C' and n > 2:
            out.append(['V', 'a'])
        elif c == 'ھ' and out and out[-1][0] == 'C':
            out[-1][1] += 'h'  # aspirate: bh, ph, kh, dh ... stays one consonant
        elif c in CONS:
            out.append(['C', CONS[c]])
        else:
            out.append(['X', c])
    # Short vowels are not written. A run of consonants gets an "a" so it is pronounceable:
    # at the start of a word after the first consonant (ka-tab), in the middle only when the run is
    # three or longer (paakistaan keeps "st" together), and never before the last consonant of a word.
    res = []
    i = 0
    m = len(out)
    while i < m:
        kind, text = out[i]
        if kind != 'C':
            res.append(text)
            i += 1
            continue
        j = i
        while j < m and out[j][0] == 'C':
            j += 1
        run = [t for _, t in out[i:j]]
        if len(run) >= 2 and run[-1] in ('y', 'w') and j < m:
            run = run[:-2] + [run[-2] + run[-1]]  # consonant + glide before a vowel: py-aar, kw-aab
        at_start = not any(k != 'X' for k, _ in out[:i])
        at_end = j == m
        if len(run) >= 2 and at_start and not at_end:
            res.append(run[0] + 'a' + ''.join(run[1:]))
        elif len(run) >= 3 and not at_start and not at_end:
            res.append(run[0] + 'i' + ''.join(run[1:]))
        elif len(run) >= 3 and at_end:
            res.append(run[0] + 'a' + ''.join(run[1:]))
        else:
            res.append(''.join(run))
        i = j
    return ''.join(res)


def cost(rank, total):
    return 1500 + int(3500 * rank / max(1, total))


def build(words, out):
    rows = {}
    for rank, w in enumerate(words):
        if any(c.isdigit() or c in '.-/' for c in w):
            continue
        r = urdu(w)
        if r and r != w:
            rows.setdefault(w, (r, cost(rank, len(words))))
    with gzip.open(out, 'wt', encoding='utf-8', compresslevel=9) as f:
        f.write(f"{HEADER}\tur\tsurface\treading\tpron-for-particles\tcost\tclass\n")
        for w, (r, c) in rows.items():
            f.write(f"{w}\t{r}\t\t{c}\tN\n")
    return len(rows)


if __name__ == '__main__':
    out = sys.argv[1] if len(sys.argv) > 1 else '.'
    if len(sys.argv) > 2 and sys.argv[2] == 'test':
        for w in ['کتاب', 'محبت', 'پاکستان', 'ہندوستان', 'بچہ', 'لڑکی', 'سمجھنا', 'گانا', 'خوبصورت', 'روشنی', 'بھول', 'کھانا', 'دوستی', 'آسمان', 'پیارا', 'موسم', 'اندھیرا', 'مسکراہٹ', 'زمین', 'شکریہ', 'گئے', 'بہت', 'ستارے', 'تنہائی']:
            print(w, urdu(w))
        sys.exit()
    print('ur', build(top_n_list('ur', 60000), f'{out}/ur.tsv.gz'))
