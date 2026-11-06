"""End-to-end API check against a running development instance with a disposable database."""
import concurrent.futures
import datetime as dt
import json
import os
import secrets
import time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

BASE = os.getenv("CAMPUS_API", "http://127.0.0.1:8080/api/")
TAG = secrets.token_hex(4)
PASSWORD = secrets.token_urlsafe(18)


def call(path, token=None, expected=200, **fields):
    headers = {"Authorization": "Bearer " + token} if token else {}
    data = None
    if fields:
        data = urlencode(fields).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    try:
        with urlopen(Request(BASE + path, data=data, headers=headers), timeout=15) as response:
            status, body = response.status, json.load(response)
    except HTTPError as error:
        status, body = error.code, json.load(error)
    assert status == expected, (path, status, body)
    return body


def stamp(seconds):
    return (dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=seconds)).isoformat()


def main():
    assert call("health") == {"status": "ok"}
    admin = call("login", username=os.getenv("BOOTSTRAP_USER", "admin"),
                 password=os.environ["BOOTSTRAP_PASSWORD"])["token"]
    group = call("groups", admin, department="ISE-" + TAG, session="2026-27",
                 year=2, semester=4, section="A")["id"]
    other_group = call("groups", admin, department="ISE-" + TAG, session="2026-27",
                       year=2, semester=4, section="B")["id"]
    subject = call("subjects", admin, name="Data Structures " + TAG)["id"]

    def account(role, suffix, **fields):
        name = suffix + "-" + TAG
        identity = call("accounts", admin, username=name, password=PASSWORD, role=role, **fields)["id"]
        return identity, call("login", username=name, password=PASSWORD)["token"]

    faculty_id, faculty = account("FACULTY", "faculty")
    student_id, student = account("STUDENT", "student", group=group)
    _, outsider = account("STUDENT", "outsider", group=other_group)
    _, observer = account("OBSERVER", "observer")
    call("faculty-scopes", admin, faculty=faculty_id, group=group, subject=subject)
    assert call("my-scopes", faculty)[0]["subject_id"] == subject
    call("banks", faculty, 403, subject=subject, groups=other_group, title="Forbidden")
    bank = call("banks", faculty, subject=subject, groups=group, title="Trees")["id"]
    fields = dict(bank=bank, a="One", b="Two", c="Three", d="Four", correct=2,
                  topic="Trees", difficulty="EASY", marks=1, penalty="0.25")
    call("questions", faculty, prompt="Question one", **fields)
    call("questions", faculty, prompt="Question two", **fields)
    call("questions", faculty, 409, prompt="Question one", **fields)
    call("bank-state", faculty, bank=bank, state="SUBMITTED")
    call("bank-state", faculty, 409, bank=bank, state="APPROVED")
    call("bank-state", admin, bank=bank, state="APPROVED")

    settings = dict(bank=bank, mode="OFFICIAL", attempts=1, duration=120, easy=2,
                    medium=0, hard=0, groups=group, title="Trees test", **{"pass": "0.5"})
    call("publish", admin, 409, opens=stamp(-20), closes=stamp(60), **(settings | {"hard": 1}))
    exam = call("publish", admin, opens=stamp(-20), closes=stamp(14), **settings)["id"]
    practice = call("publish", admin, opens=stamp(-20), closes=stamp(90),
                    **(settings | {"mode": "PRACTICE", "attempts": 2}))["id"]
    call("bank-state", admin, bank=bank, state="ARCHIVED")
    assert not any(e["id"] == exam for e in call("exams", outsider))
    call("start", outsider, 404, exam=exam)
    assert call(f"monitor?exam={exam}", observer)[0]["status"] == "NOT_STARTED"
    call("release", observer, 403, exam=exam)
    call("release", admin, 409, exam=exam)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        ids = set(pool.map(lambda _: call("start", student, exam=exam)["id"], range(4)))
    assert len(ids) == 1
    attempt = ids.pop()
    call(f"attempt?attempt={attempt}", outsider, 404)
    questions = call(f"attempt?attempt={attempt}", student)["questions"]
    assert len(questions) == 2 and all(q["correct"] is None for q in questions)
    answer = dict(attempt=attempt, position=1, choice=2, review="true", version=2)
    assert call("answer", student, **answer) == {"status": "SAVED", "version": 2}
    assert call("answer", student, **answer) == {"status": "SAVED", "version": 2}
    call("answer", student, 409, **(answer | {"version": 1}))
    call("answer", student, 409, **(answer | {"choice": 1}))
    call("answer", student, **(answer | {"position": 2, "choice": 1}))
    call("heartbeat", student, attempt=attempt, pending=1)
    state = call(f"monitor?exam={exam}", observer)[0]
    assert state["student_id"] == student_id and state["answered"] == 2 and state["reported_pending"] == 1
    assert call(f"attempt?attempt={attempt}", student)["questions"][0]["choice"] == 2

    # Wait for the background worker without reading the attempt or manually submitting.
    for _ in range(25):
        state = call(f"monitor?exam={exam}", admin)[0]
        if state["status"] == "SUBMITTED":
            break
        time.sleep(1)
    assert state["status"] == "SUBMITTED" and state["score"] == 0.75
    assert call(f"attempt?attempt={attempt}", student)["reason"] == "DEADLINE"
    assert call("submit", student, attempt=attempt)["status"] == "SUBMITTED"
    assert call("answer", student, **(answer | {"version": 3}))["status"] == "SUBMITTED"
    assert call("start", student, exam=exam)["id"] == attempt
    assert call(f"attempt?attempt={attempt}", student)["score"] is None
    call("release", admin, exam=exam)
    result = call(f"attempt?attempt={attempt}", student)
    assert result["score"] == 0.75 and result["passed"]
    assert all(q["correct"] == 2 for q in result["questions"])
    first = call("start", student, exam=practice)["id"]
    call("submit", student, attempt=first)
    practice_result = call(f"attempt?attempt={first}", student)
    assert practice_result["reason"] == "MANUAL" and practice_result["score"] == 0
    assert all(q["correct"] is None for q in practice_result["questions"])
    second = call("start", student, exam=practice)["id"]
    assert second != first
    call("submit", student, attempt=second)
    assert call("start", student, exam=practice)["id"] == second
    print("CampusExam database-backed smoke test passed.")


if __name__ == "__main__":
    main()
