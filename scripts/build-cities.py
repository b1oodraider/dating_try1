"""Builds backend/src/main/resources/db/data/cities_ru.csv and city_names_ru.csv from GeoNames (CC BY 4.0).

Cities: populated places in Russia with >= 1000 people that have a Russian name.
Names: official Russian name, other Russian names (kind ALT), colloquial (COLLOQUIAL), historic (HISTORIC),
plus our own colloquial aliases.

Download into one folder (https://download.geonames.org/export/dump/): RU.zip -> ru/RU.txt,
alternatenames/RU.zip -> alt/RU.txt, admin1CodesASCII.txt -> admin1.txt. Then:
    python scripts/build-cities.py <that folder> <repo root>
and add a new Liquibase changeset that reloads the tables (the 023 one never runs again).
"""
import csv
import os
import re
import sys

HERE = sys.argv[1]
REPO = sys.argv[2]
OUT = os.path.join(REPO, 'backend', 'src', 'main', 'resources', 'db', 'data')
MIN_POPULATION = 1000
CODES = {'PPL', 'PPLA', 'PPLA2', 'PPLA3', 'PPLA4', 'PPLC', 'PPLG', 'PPLS'}
CYRILLIC = re.compile(r'[а-яё]', re.I)

# Our own slang, by GeoNames id (the dictionaries do not have them).
ALIASES = {
    '524901': ['Мск', 'Масква', 'Белокаменная'],        # Москва
    '498817': ['Спб', 'Питер', 'Петербург', 'Северная столица'],  # Санкт-Петербург
    '1486209': ['Екб', 'Ебург', 'Екат'],                 # Екатеринбург
    '1496747': ['Нск', 'Новосиб'],                       # Новосибирск
    '520555': ['Нижний', 'Нижний Новгород', 'Нн'],       # Нижний Новгород
    '501175': ['Ростов', 'Ростов-на-Дону'],               # Ростов-на-Дону
    '1508291': ['Челяба'],                               # Челябинск
    '2013348': ['Владик'],                               # Владивосток
    '551487': ['Казань'],                                # Казань
    '499099': ['Самара'],                                # Самара
    '542420': ['Краснодар', 'Кдр'],                      # Краснодар
    '1502026': ['Красноярск', 'Крск'],                   # Красноярск
}


def norm(text):
    text = text.lower().replace('ё', 'е')
    text = re.sub(r'[^0-9a-zа-я]+', ' ', text)
    return text.strip()


def main():
    places = {}
    with open(os.path.join(HERE, 'ru', 'RU.txt'), encoding='utf-8') as f:
        for r in csv.reader(f, delimiter='\t', quoting=csv.QUOTE_NONE):
            if r[6] == 'P' and r[7] in CODES and int(r[14] or 0) >= MIN_POPULATION:
                places[r[0]] = {'lat': r[4], 'lon': r[5], 'population': int(r[14]), 'admin1': r[10], 'tz': r[17]}
    admin1 = {}
    with open(os.path.join(HERE, 'admin1.txt'), encoding='utf-8') as f:
        for r in csv.reader(f, delimiter='\t', quoting=csv.QUOTE_NONE):
            if r[0].startswith('RU.'):
                admin1[r[0][3:]] = r[3]  # admin1 code -> geonameid of the region

    names = {}            # geonameid -> list of (name, kind, preferred)
    with open(os.path.join(HERE, 'alt', 'RU.txt'), encoding='utf-8') as f:
        for r in csv.reader(f, delimiter='\t', quoting=csv.QUOTE_NONE):
            gid, lang, name = r[1], r[2], r[3]
            if not CYRILLIC.search(name) or lang not in ('ru', ''):
                continue
            kind = 'HISTORIC' if r[7] == '1' else 'COLLOQUIAL' if r[6] == '1' else 'ALT'
            names.setdefault(gid, []).append((name, kind, r[4] == '1' and lang == 'ru'))

    def official(gid):
        candidates = names.get(gid, [])
        for name, kind, pref in candidates:
            if pref and kind == 'ALT':
                return name
        for name, kind, _ in candidates:
            if kind == 'ALT':
                return name
        return None

    os.makedirs(OUT, exist_ok=True)
    kept = 0
    with open(os.path.join(OUT, 'cities_ru.csv'), 'w', encoding='utf-8', newline='') as fc, \
            open(os.path.join(OUT, 'city_names_ru.csv'), 'w', encoding='utf-8', newline='') as fn:
        wc = csv.writer(fc)
        wn = csv.writer(fn)
        wc.writerow(['id', 'name', 'region', 'latitude', 'longitude', 'population', 'timezone'])
        wn.writerow(['city_id', 'name', 'norm', 'kind'])
        for gid, p in sorted(places.items(), key=lambda kv: -kv[1]['population']):
            name = official(gid)
            if not name:
                continue
            region = official(admin1.get(p['admin1'], '')) or ''
            wc.writerow([gid, name, region, p['lat'], p['lon'], p['population'], p['tz']])
            kept += 1
            seen = set()
            rows = [(name, 'OFFICIAL')] + [(n, k) for n, k, _ in names.get(gid, [])] \
                + [(a, 'COLLOQUIAL') for a in ALIASES.get(gid, [])]
            for n, k in rows:
                key = norm(n)
                if key and key not in seen:
                    seen.add(key)
                    wn.writerow([gid, n, key, k])
    print('cities', kept)


main()
