CREATE TABLE IF NOT EXISTS academic_group (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 department text NOT NULL, academic_session text NOT NULL, study_year int NOT NULL CHECK (study_year > 0),
 semester int NOT NULL CHECK (semester > 0), section text NOT NULL,
 UNIQUE(department,academic_session,study_year,semester,section)
);
CREATE TABLE IF NOT EXISTS account (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, username text NOT NULL UNIQUE,
 password_hash text NOT NULL, role text NOT NULL CHECK(role IN ('ADMIN','FACULTY','STUDENT','OBSERVER')),
 group_id bigint REFERENCES academic_group, CHECK ((role='STUDENT')=(group_id IS NOT NULL))
);
CREATE TABLE IF NOT EXISTS session_token (
 token_hash text PRIMARY KEY, account_id bigint NOT NULL REFERENCES account, expires timestamptz NOT NULL
);
CREATE TABLE IF NOT EXISTS subject (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name text NOT NULL UNIQUE
);
CREATE TABLE IF NOT EXISTS faculty_scope (
 faculty_id bigint REFERENCES account, group_id bigint REFERENCES academic_group, subject_id bigint REFERENCES subject,
 PRIMARY KEY(faculty_id,group_id,subject_id)
);
CREATE TABLE IF NOT EXISTS bank (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, owner_id bigint NOT NULL REFERENCES account,
 subject_id bigint NOT NULL REFERENCES subject, title text NOT NULL,
 state text NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','ARCHIVED'))
);
CREATE TABLE IF NOT EXISTS bank_scope (
 bank_id bigint REFERENCES bank, group_id bigint REFERENCES academic_group, PRIMARY KEY(bank_id,group_id)
);
CREATE TABLE IF NOT EXISTS question (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, bank_id bigint NOT NULL REFERENCES bank,
 prompt text NOT NULL, options text[] NOT NULL CHECK(cardinality(options)=4),
 correct int NOT NULL CHECK(correct BETWEEN 1 AND 4), explanation text NOT NULL,
 topic text NOT NULL, difficulty text NOT NULL CHECK(difficulty IN ('EASY','MEDIUM','HARD')),
 marks numeric(8,2) NOT NULL CHECK(marks>0), penalty numeric(8,2) NOT NULL CHECK(penalty>=0 AND penalty<=marks)
);
CREATE UNIQUE INDEX IF NOT EXISTS question_prompt_key ON question(bank_id,lower(btrim(prompt)));
CREATE TABLE IF NOT EXISTS exam (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, title text NOT NULL, instructions text NOT NULL,
 bank_id bigint NOT NULL REFERENCES bank, mode text NOT NULL CHECK(mode IN ('OFFICIAL','PRACTICE')),
 duration int NOT NULL CHECK(duration BETWEEN 1 AND 28800),
 attempt_limit int NOT NULL CHECK(attempt_limit BETWEEN 1 AND 100),
 opens timestamptz NOT NULL, closes timestamptz NOT NULL CHECK(closes>opens),
 pass_marks numeric(12,2) NOT NULL CHECK(pass_marks>=0), released boolean NOT NULL DEFAULT false
);
CREATE TABLE IF NOT EXISTS exam_question (
 exam_id bigint REFERENCES exam, position int CHECK(position>0), prompt text NOT NULL,
 options text[] NOT NULL, correct int NOT NULL, explanation text NOT NULL,
 topic text NOT NULL, difficulty text NOT NULL, marks numeric(8,2) NOT NULL, penalty numeric(8,2) NOT NULL,
 PRIMARY KEY(exam_id,position)
);
CREATE TABLE IF NOT EXISTS assignment (
 exam_id bigint REFERENCES exam, student_id bigint REFERENCES account, PRIMARY KEY(exam_id,student_id)
);
CREATE TABLE IF NOT EXISTS attempt (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, exam_id bigint NOT NULL, student_id bigint NOT NULL,
 started timestamptz NOT NULL, deadline timestamptz NOT NULL, last_seen timestamptz NOT NULL,
 submitted timestamptz, reason text, score numeric(12,2), pending int NOT NULL DEFAULT 0,
 UNIQUE(id,exam_id), FOREIGN KEY(exam_id,student_id) REFERENCES assignment
);
CREATE UNIQUE INDEX IF NOT EXISTS one_active_attempt ON attempt(exam_id,student_id) WHERE submitted IS NULL;
CREATE INDEX IF NOT EXISTS attempt_due ON attempt(deadline) WHERE submitted IS NULL;
CREATE TABLE IF NOT EXISTS answer (
 attempt_id bigint, exam_id bigint NOT NULL, position int, choice int CHECK(choice BETWEEN 1 AND 4),
 review boolean NOT NULL, version bigint NOT NULL CHECK(version>0),
 PRIMARY KEY(attempt_id,position), FOREIGN KEY(attempt_id,exam_id) REFERENCES attempt(id,exam_id),
 FOREIGN KEY(exam_id,position) REFERENCES exam_question
);
CREATE TABLE IF NOT EXISTS audit (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, happened timestamptz NOT NULL DEFAULT clock_timestamp(),
 actor bigint REFERENCES account, action text NOT NULL, entity bigint NOT NULL, detail text NOT NULL DEFAULT ''
);

ALTER TABLE exam ADD COLUMN IF NOT EXISTS paused_at timestamptz;
CREATE TABLE IF NOT EXISTS incident (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 attempt_id bigint NOT NULL REFERENCES attempt,
 detail text NOT NULL, reported timestamptz NOT NULL DEFAULT clock_timestamp(),
 resolution text, resolved timestamptz
);
ALTER TABLE question ADD COLUMN IF NOT EXISTS image text;
ALTER TABLE exam_question ADD COLUMN IF NOT EXISTS image text;
CREATE TABLE IF NOT EXISTS import_receipt (
 bank_id bigint REFERENCES bank, fingerprint text, imported int NOT NULL,
 PRIMARY KEY(bank_id,fingerprint)
);
ALTER TABLE exam ADD COLUMN IF NOT EXISTS navigation text NOT NULL DEFAULT 'FREE' CHECK(navigation IN ('FREE','FORWARD'));
ALTER TABLE attempt ADD COLUMN IF NOT EXISTS current_position int NOT NULL DEFAULT 1 CHECK(current_position>0);
ALTER TABLE exam ADD COLUMN IF NOT EXISTS practice_feedback text NOT NULL DEFAULT 'SCORE'
 CHECK(practice_feedback IN ('DELAYED','SCORE','EXPLANATIONS'));
