"""Sum JUnit XML results: count_tests.py build/test-results/test"""
import glob, os, sys, xml.etree.ElementTree as ET
t = f = e = s = n = 0
for p in glob.glob(os.path.join(sys.argv[1], "*.xml")):
    r = ET.parse(p).getroot(); n += 1
    t += int(r.get("tests", 0)); f += int(r.get("failures", 0)); e += int(r.get("errors", 0)); s += int(r.get("skipped", 0))
print(f"suites={n} tests={t} failures={f} errors={e} skipped={s}")
