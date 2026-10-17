#!/bin/sh
# Run before launch. Exits 1 if anything is unfinished.
cd "$(dirname "$0")/src/main/resources/static" || exit 1; f=0
grep -rnoa '{{[A-Z_]*}}' . && { echo "^ fill in these placeholders (operator name, contact email, governing law)"; f=1; }
for x in favicon.svg favicon.ico apple-touch-icon.png privacy.html terms.html index.html; do [ -f "$x" ] || { echo "missing $x"; f=1; }; done
grep -rniaE "made with|$(printf '\342\200\224')|$(printf '\342\200\223')" . && { echo "^ remove these"; f=1; }
[ $f = 0 ] && echo "Launch checks passed (custom domain and HTTPS still need to be set up, see README)"
exit $f
