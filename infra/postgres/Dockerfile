# PostgreSQL with the Russian Hunspell dictionary for full-text search (exclusion words).
# Dictionary: (c) Alexander I. Lebedev, BSD-style license; packaged for PostgreSQL by Postgres Professional
# (https://github.com/postgrespro/hunspell_dicts). Files are pinned to a commit and verified by SHA-256.
FROM postgres:16-alpine

ARG HUNSPELL_REF=918f8d093b1b6cd5f7e4c6b4874f559d7210b207
ARG HUNSPELL_BASE=https://raw.githubusercontent.com/postgrespro/hunspell_dicts/${HUNSPELL_REF}/hunspell_ru_ru

RUN set -eu; \
    dir="$(pg_config --sharedir)/tsearch_data"; \
    wget -q -O "$dir/ru_ru.affix" "$HUNSPELL_BASE/ru_ru.affix"; \
    wget -q -O "$dir/ru_ru.dict" "$HUNSPELL_BASE/ru_ru.dict"; \
    echo "9cf4c27307af21544ef461b3ecbceecf0eec6d93ee8494eb19cab0de63692441  $dir/ru_ru.affix" | sha256sum -c -; \
    echo "0963e4a7c06f922fe6e415c8303c20995a617e1d1c2acfae8b819c2f89c52101  $dir/ru_ru.dict" | sha256sum -c -; \
    mkdir -p /usr/share/licenses/hunspell-ru; \
    wget -q -O /usr/share/licenses/hunspell-ru/LICENSE.txt "$HUNSPELL_BASE/license/README_en.txt"
