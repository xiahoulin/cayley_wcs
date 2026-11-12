"""Check a Docker JVM against MyBatis extracted from the actual WCS boot JAR.

Requires javac 25+ on the host and a private work directory outside the checkout.
No network, database, device endpoint or application write is used.
Successful runs prove C2 compilation and result correctness, not reproduction of
an intermittent JVM crash or attribution to a particular upstream bug.
"""
import argparse
import json
import pathlib
import re
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=pathlib.Path)
    parser.add_argument("--runtime-image", required=True)
    parser.add_argument("--work-dir", required=True, type=pathlib.Path)
    parser.add_argument("--javac", default="javac")
    args = parser.parse_args()
    repo = pathlib.Path(__file__).resolve().parents[1]
    work = args.work_dir.resolve()
    if work == repo or repo in work.parents:
        parser.error("Use a private work directory outside the WCS checkout")
    work.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.jar) as boot:
        libraries = [n for n in boot.namelist() if re.fullmatch(r"BOOT-INF/lib/mybatis-\d[^/]+\.jar", n)]
        if len(libraries) != 1:
            raise RuntimeError("Expected exactly one packaged MyBatis core JAR")
        library = work / pathlib.PurePosixPath(libraries[0]).name
        library.write_bytes(boot.read(libraries[0]))
    subprocess.run([args.javac, "-cp", str(library), "-d", str(work / "classes"),
                    str(repo / "scripts/jvm/MyBatisC2Probe.java")], check=True)
    result = subprocess.run([
        "docker", "run", "--rm", "--network", "none",
        "--mount", f"type=bind,source={work},target=/probe", "--workdir", "/probe",
        "--entrypoint", "java", args.runtime_image, "-XX:+PrintCompilation",
        "-XX:ErrorFile=/probe/hs_err_pid%p.log", "-cp", f"/probe/classes:/probe/{library.name}",
        "org.apache.ibatis.scripting.xmltags.MyBatisC2Probe",
    ], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180)
    (work / "probe.stdout.log").write_text(result.stdout, encoding="utf-8")
    (work / "probe.stderr.log").write_text(result.stderr, encoding="utf-8")
    c2 = re.findall(r"^.*\s4\s+org\.apache\.ibatis\.scripting\.xmltags\.DynamicContext\$ContextMap::get.*$",
                    result.stdout, flags=re.MULTILINE)
    passed = result.returncode == 0 and bool(c2) and "MYBATIS_C2_PROBE_OK" in result.stdout
    evidence = {"passed": passed, "exitCode": result.returncode, "runtimeImage": args.runtime_image,
                "mybatis": library.name, "c2Compilations": c2,
                "result": [line for line in result.stdout.splitlines() if "MYBATIS_C2_PROBE_OK" in line]}
    (work / "evidence.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
    print(json.dumps(evidence, ensure_ascii=False))
    if not passed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
