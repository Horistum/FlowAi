"""Separate disposable signing roles for the installed portfolio review proof."""
import base64
import subprocess
import sys

from authoring_host.contracts import canonical, digest, loads, require


class PortfolioReviewFixture:
    def __init__(self, root, work, portfolio, output):
        self.work = work
        self.command = [sys.executable, str(root / "tools/review_planning_portfolio.py")]
        self.output = ["--out", str(output)]
        self.registration_path = output / "registration.json"
        policy = dict(formatVersion=1, evidenceKind="scripted", providerId="test-custodian", reviewerId="test-reviewer",
                      requiredDomains=["infrastructure", "data-recovery"], heldOutCases=["recovery", "wrong-goal"])
        for role in ("reviewer", "provider"):
            key = work / (role + ".pem")
            subprocess.run(["openssl", "genpkey", "-algorithm", "ED25519", "-out", str(key)], check=True, capture_output=True)
            public = subprocess.check_output(["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER"])
            policy[role + "Key"] = base64.b64encode(public).decode()
        self.policy = work / "exposure.json"
        self.policy.write_bytes(canonical(policy))
        self.register = ["register", "--portfolio", str(portfolio), "--policy", str(self.policy), "--max-turns", "9"]
        registered = self.call(self.register)
        self.pin = ["--registration-sha256", registered["registrationSha256"]]
        require(self.call(self.register) == registered, "Pre-run registration changed during recovery")
        require(self.call(["request", *self.pin], 3)["status"] == "incomplete", "Empty trial matrix became reviewable")

    def call(self, args, expected=0):
        result = subprocess.run([*self.command, *args, *self.output], capture_output=True, timeout=180)
        require(result.returncode == expected, f"Portfolio command {args[0]} exited {result.returncode}: {result.stderr[-300:]!r}")
        return loads(result.stdout) if result.stdout else None

    def sign(self, name, role, doc):
        path = self.work / (name + ".json")
        path.write_bytes(canonical(doc))
        subprocess.run(["openssl", "pkeyutl", "-sign", "-rawin", "-inkey", str(self.work / (role + ".pem")),
                        "-in", str(path), "-out", str(path.with_suffix(".sig"))], capture_output=True, check=True)
        return digest(path.read_bytes())

    def verify(self):
        saved = self.registration_path.read_bytes()
        self.registration_path.unlink()
        try:
            self.call(self.register, 2)
        finally:
            self.registration_path.write_bytes(saved)
        self.call(["request", "--registration-sha256", "0" * 64], 2)
        request = self.call(["request", *self.pin])
        turns = [loads(line) for line in (self.work / "turns.jsonl").read_bytes().splitlines()]
        export = dict(formatVersion=1, registrationSha256=self.pin[-1], providerId="test-custodian",
                      evidenceKind="scripted", turns=list(reversed(turns)))
        report = dict(formatVersion=1, requestSha256=digest(canonical(request)), reviewerId="test-reviewer",
                      trials=[dict(id=row["id"], assessment="wrong-goal" if row["case"] == "wrong-goal" else "faithful",
                                   reason="Compare the selected plan with the original recovery or foundation request.")
                              for row in request["trials"]])
        def publish(records, signing_role="provider"):
            report["providerRecordsSha256"] = self.sign("provider", signing_role, records)
            self.sign("reviewer", "reviewer", report)
        args = ["assess", *self.pin, "--provider-records", str(self.work / "provider.json"),
                "--provider-signature", str(self.work / "provider.sig"), "--review", str(self.work / "reviewer.json"),
                "--review-signature", str(self.work / "reviewer.sig")]
        publish(export)
        result = self.call(args, 3)
        require(result["status"] == "test-evidence" and result["faithfulPerDomain"] == {"infrastructure": 3, "data-recovery": 3}
                and result["totalCostMicrousd"] is None and sum(r["passed"] for r in result["trials"]) == 6,
                "Scripted proof lost trial outcomes or promoted live provenance")
        for changed in (dict(export, evidenceKind="live-provider"), dict(export, turns=turns[:-1]),
                        dict(export, turns=[turns[0], *turns[:-1]]),
                        dict(export, turns=[dict(turns[0], drafts=[]), *turns[1:]])):
            publish(changed)
            self.call(args, 2)
        publish(export, "reviewer")
        self.call(args, 2)
        publish(export)
        review = self.work / "reviewer.json"
        review.write_bytes(review.read_bytes() + b" ")
        self.call(args, 2)
        require(len(turns) == 9 and (self.work / "home/turn-count").read_text() == "9",
                "Independent assessment dispatched extra turns")
