# CampusExam

CampusExam is a browser-based exam system with a Java 17 HTTP API and PostgreSQL. The college server runs the API and stores all exam data locally. Clients use the campus LAN. The application makes no outbound internet calls at runtime.

## How the reference materials informed the build

The workflow PDF and PowerPoint describe the same core flow: academic setup, faculty question entry, COE approval and publication, student attempts, server scoring and result release. The UI PDF adds a COE dashboard and a HOD/Principal overview. This backend maps COE to ADMIN and HOD/Principal to a campus-wide read-only OBSERVER. Students sign in with a USN and password, then choose from assigned exams. The mockup's subject code serves as a screen concept. Eligibility is enforced through assignments.

Java replaces the proposed Node.js backend. PostgreSQL remains the database. The API accepts form-encoded requests and returns JSON and serves a bundled browser portal. Both API and browser assets run locally.

## Run locally on Windows

1. Install JDK 17 and PostgreSQL. In PostgreSQL, create a login named campusexam and a fresh database named campusexam owned by that login. For example, connect as the PostgreSQL administrator and run the following in psql.

   ```sql
   CREATE ROLE campusexam LOGIN;
   \password campusexam
   CREATE DATABASE campusexam OWNER campusexam;
   ```

2. Put a PostgreSQL JDBC jar compatible with Java 17 at lib/postgresql.jar, or set PG_JDBC_JAR to its absolute path. The test uses pgJDBC 42.7.5. The PostgreSQL JDBC project distributes the driver at https://jdbc.postgresql.org/download/. Download or transfer the jar before moving the server onto the isolated LAN.
3. Open PowerShell 7 in the project directory. Set passwords when prompted, then start the API.

   ```powershell
   $env:DB_PASSWORD = Read-Host 'PostgreSQL password' -MaskInput
   $env:BOOTSTRAP_PASSWORD = Read-Host 'Initial CampusExam admin password (12+ characters)' -MaskInput
   .\run.ps1
   ```

   The bootstrap username defaults to admin. BOOTSTRAP_USER overrides it at first startup. The bootstrap password is only used when no admin exists. The application applies schema.sql to the fresh database on startup. Use a dedicated database. DB_URL and DB_USER override the default jdbc:postgresql://localhost:5432/campusexam and campusexam.

4. Open http://127.0.0.1:8080 in a browser. The portal links lead to the Student, Faculty, COE and HOD/Principal sign-in screens. To check the API directly from PowerShell, use the following commands.

   ```powershell
   $api = 'http://127.0.0.1:8080/api'
   Invoke-RestMethod "$api/health"
   $login = Invoke-RestMethod "$api/login" -Method Post -Body @{
       username = 'admin'
       password = Read-Host 'CampusExam admin password' -MaskInput
   }
   $headers = @{ Authorization = "Bearer $($login.token)" }
   Invoke-RestMethod "$api/groups" -Headers $headers
   ```

The API binds to loopback by default. For campus access, terminate HTTPS on a local reverse proxy and forward /api/ to the loopback listener. Forward the portal routes and static assets through the same proxy origin. Configure the lab clients to trust the campus certificate. PORT changes the loopback port and BIND changes the listen address. Linux can run the same Java sources with javac --release 17 -d build CampusExam.java Workbook.java and java -cp 'build:lib/postgresql.jar' CampusExam from the project directory.

## API contract

Every route below is relative to /api/. POST data uses application/x-www-form-urlencoded. Except health and login, requests use Authorization: Bearer TOKEN. Success is HTTP 200. Typical errors are 400 for invalid fields, 401 for missing or expired authentication, 403 for role or scope violations, 404 for an unavailable resource and 409 for lifecycle or version conflicts.

| Method and route | Role | Fields and behavior |
| --- | --- | --- |
| GET health | Public | Checks database connectivity and returns status. |
| POST login | Public | username, password. Returns token and role. |
| POST logout | Signed in | Empty form body. Revokes the current token. |
| GET / POST groups | Admin | POST department, session, year, semester, section. |
| GET / POST subjects | Admin | POST name. |
| GET / POST accounts | Admin | POST username, password, role, and group for STUDENT. Roles use uppercase names. |
| GET / POST faculty-scopes | Admin | POST faculty, group, subject IDs. |
| GET my-scopes | Faculty | Lists the signed-in faculty member's assigned scopes. |
| GET / POST banks | Admin, Faculty | POST title, subject ID and groups (comma-separated IDs). Faculty can author banks they own in assigned scopes. |
| GET / POST questions | Admin, Faculty | GET bank query parameter. POST bank, prompt, a, b, c, d, correct (1-4), optional explanation, topic, difficulty (EASY, MEDIUM, HARD), marks, penalty. |
| POST bank-state | Admin, Faculty | bank, state. Faculty can submit their own draft or reopen a rejected bank. Admin can approve, reject or archive under the lifecycle rules. |
| POST publish | Admin | bank, title, optional instructions, mode (OFFICIAL or PRACTICE), duration (seconds), attempts, opens and closes (ISO 8601 with offset), pass, easy, medium, hard (counts), groups (comma-separated IDs), optional students (comma-separated account IDs), navigation (FREE or FORWARD), and practice feedback (DELAYED, SCORE or EXPLANATIONS). |
| GET exams | Student, Admin, Observer, Faculty | Students see their assignments. Faculty see exams for which they have subject and group access for every assigned student. Admin and Observer see the campus catalog. |
| POST start | Student | exam ID. Returns an attempt ID. Resumes an active attempt and returns the last attempt when the attempt limit is exhausted. |
| GET attempts | Student | exam query parameter. Returns the student's own attempt history for the exam. |
| GET attempt | Student | attempt query parameter. Returns deadline, server time, questions, committed answers and any visible result. |
| POST answer | Student | attempt, position (1-based), choice (1-4, or 0 to clear), review (true or false), version (positive integer). |
| POST heartbeat | Student | attempt, pending (number of locally pending answers). |
| POST submit | Student | attempt. Safe to repeat. |
| GET monitor | Admin, Observer, scoped Faculty | exam query parameter. Returns each assigned student's latest attempt, answer count, remaining time and reported pending count. |
| POST release | Admin | exam. Available after the window closes and all attempts are scored. |
| POST preview | Admin | Uses publication fields. Validates eligibility and difficulty, and returns sample questions without committing publication. |
| POST import-accounts | Admin | rows is a JSON array of student or faculty account objects. Rolls back on any invalid row. |
| POST import-questions | Admin, Faculty | bank and rows, a JSON array using question fields. An identical successful batch is idempotent. |
| POST xlsx-preview | Admin, Faculty | file is a base64 XLSX. Reads the first worksheet with up to 500 data rows and the template columns. |
| POST revise-bank / delete-question | Admin, Faculty | Copy a bank to a new draft with bank and title, or remove a draft question using bank and question. |
| POST next-question | Student | attempt and position. Advances a forward-only attempt by one question. |
| POST exam-control | Admin | exam, command (START, PAUSE, RESUME, EXTEND, CLOSE), reason and seconds for EXTEND. |
| POST incident / resolve-incident | Student / Admin | Students send attempt and detail. Admins send incident ID and resolution detail. |
| GET incidents | Admin, Observer | exam query parameter. Lists incident reports and resolutions. |
| GET analytics / xlsx-report | Admin, Observer, scoped Faculty | exam query parameter. Returns group and question statistics or a base64 Excel workbook. |
| GET audit | Admin | Returns the latest 200 audit records. |

### Exam and synchronization rules

- Publication selects the configured number of questions per difficulty and copies the selected questions, options, marking rules and private answer keys into an exam snapshot. Assignments include students in the chosen groups at publication time. Exam settings have no update API.
- Official exams allow one attempt. Practice exams allow the configured number of attempts. Practice feedback can be delayed, show the score immediately, or show scores and explanations immediately. Official marks and answer keys stay private until release.
- PostgreSQL time determines the deadline, capped by the exam's closing time. The background worker finalizes expired attempts. The attempt endpoints also check expiry. Final submission calculates and commits the score and audit event in the same transaction. Correct answers earn marks, wrong answers incur the configured deduction, and blanks earn zero. Scores can be negative.
- For each question, begin at version 1 and increment on changes. An identical retry of the current version receives SAVED, a stale or conflicting version receives HTTP 409, and an answer sent after submission receives SUBMITTED. The API emits SAVED only after the transaction commits. After reconnecting, read the attempt and reconcile the pending local changes with server versions. A client can send heartbeats every 15 seconds. Monitoring marks a session disconnected after 45 seconds of silence and treats pending counts as the client's last report.
- Practice retakes use a fresh POST start after submission while the window is open. A client should query attempt history after an ambiguous start response before intentionally starting another practice attempt.
- Credentials use salted PBKDF2-HMAC-SHA256, and opaque bearer sessions expire after 12 hours. The workflow proposed Argon2id. PBKDF2 uses the built-in Java provider and keeps the JDBC driver as the only external runtime library.

## Verify

Run python smoke_test.py against a disposable instance with BOOTSTRAP_PASSWORD set to that instance's admin password. Set CAMPUS_API if the address differs from http://127.0.0.1:8080/api/. The test creates uniquely named records and leaves them in the database for inspection.

## Browser workflow

1. In the COE portal, add an academic group and subject. Create one faculty and one student account, then grant the faculty account scope for the group and subject. The roster import accepts CSV.
2. In the Faculty portal, create a question bank for that scope. Enter questions individually, attach a PNG or JPEG when needed, or download the question template and preview a CSV or XLSX import. Imports are atomic and report the first invalid row. Submit the bank.
3. In the COE portal, approve the bank. Set the exam window, eligibility, difficulty mix, attempt limit and navigation policy. Validate and preview the exam, then publish. The preview draws a sample and publication draws its own frozen question selection.
4. In the Student portal, sign in and start the assigned exam. Answer or flag questions, check the navigator and submit. For forward-only exams, confirm the local answer changes before moving to the next question. During a connection interruption, the open browser tab keeps unconfirmed changes in IndexedDB and retries. If the tab closes while disconnected, reopen the portal when the server is reachable and resume the attempt.
5. Use COE live monitoring to review attempts, scores and incidents. COE can start a scheduled exam early, pause, resume, extend or close it with an audit reason. Export reports to CSV or Excel, or choose Print / save PDF. Release results after all attempts are scored. The HOD/Principal portal is campus-wide and read-only. Faculty reporting is limited by assigned scopes.

## Back up and restore

Use the PostgreSQL tools for your installed version. Set connection parameters or use a password prompt. Store dumps on a separate campus disk with restricted access.

```powershell
pg_dump -h localhost -U campusexam -Fc -f campusexam.dump campusexam
createdb -h localhost -U campusexam campusexam_restore
pg_restore -h localhost -U campusexam --exit-on-error -d campusexam_restore campusexam.dump
```

Check restored users, banks, exams, attempts, answers and results in an isolated instance before using a restored database. The account running createdb needs database creation privileges. The application database login can remain limited to ownership of its own database.

## Campus deployment checks

The local build can run on the laptop. Before a real campus exam, configure HTTPS, a campus LAN address, rate limiting, restricted lab accounts, physical invigilation and scheduled backups. Test the complete exam duration, physical LAN interruptions, application and database outages, and recovery on the campus hardware with its real student devices. The local concurrency test uses a single laptop and synthetic requests. Benchmark the real workload before selecting server capacity or a connection pool.

The application uses built-in PBKDF2 instead of the proposed Argon2id. Exam scheduling is configured at publication, and the preview is temporary. The browser renders the reference workflows in a responsive web interface. The two HOD/Principal roles shown in the reference share one Observer role. Academic groups store department, session, year, semester and section, with a subject catalog and question topic labels.
