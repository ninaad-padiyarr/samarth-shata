import com.sun.net.httpserver.*;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class CampusExam {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String EMPTY = "{}";

    record User(long id, String role) {
        void require(String... roles) {
            check(Arrays.asList(roles).contains(role), 403, "Forbidden");
        }
    }

    static final class Problem extends RuntimeException {
        final int status;
        Problem(int status, String message) { super(message); this.status = status; }
    }

    record Form(Map<String,String> values) {
        String get(String key) { return values.getOrDefault(key, ""); }
        String need(String key) {
            String value = get(key).strip();
            check(!value.isEmpty() && value.length() <= 8000, 400, "Invalid " + key);
            return value;
        }
        long id(String key) {
            long value = Long.parseLong(need(key));
            check(value > 0, 400, "Invalid " + key);
            return value;
        }
        int number(String key) { return Integer.parseInt(need(key)); }
        BigDecimal decimal(String key) {
            BigDecimal value = new BigDecimal(need(key));
            check(value.precision() <= 12 && value.scale() >= 0 && value.scale() <= 2, 400, "Invalid " + key);
            return value;
        }
    }

    static void check(boolean condition, int status, String message) {
        if (!condition) throw new Problem(status, message);
    }

    static Connection connect() throws SQLException {
        return DriverManager.getConnection(env("DB_URL", "jdbc:postgresql://localhost:5432/campusexam"),
            env("DB_USER", "campusexam"), env("DB_PASSWORD", ""));
    }

    static String env(String key, String fallback) { return System.getenv().getOrDefault(key, fallback); }

    public static void main(String[] args) throws Exception {
        try (Connection db = connect()) {
            db.setAutoCommit(false);
            try (Statement statement = db.createStatement()) {
                statement.execute(Files.readString(Path.of("schema.sql")));
            }
            if ("".equals(one(db, "SELECT id FROM account WHERE role='ADMIN' LIMIT 1"))) {
                String password = env("BOOTSTRAP_PASSWORD", "");
                validatePassword(password);
                run(db, "INSERT INTO account(username,password_hash,role) VALUES(?,?,'ADMIN')",
                    env("BOOTSTRAP_USER", "admin").strip().toLowerCase(Locale.ROOT), passwordHash(password));
            }
            db.commit();
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(env("BIND", "127.0.0.1"),
            Integer.parseInt(env("PORT", "8080"))), 128);
        server.createContext("/api/", CampusExam::handle);
        server.createContext("/", CampusExam::servePortal);
        server.setExecutor(Executors.newFixedThreadPool(16));
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(CampusExam::expire, 0, 2, TimeUnit.SECONDS);
        server.start();
        System.out.println("CampusExam API listening on " + server.getAddress());
    }

    static void servePortal(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String file = switch (path) {
            case "/", "/admin", "/faculty", "/observer" -> "index.html";
            case "/portal.js" -> "portal.js";
            case "/portal.css" -> "portal.css";
            default -> null;
        };
        if (file == null || !exchange.getRequestMethod().equals("GET")) {
            exchange.sendResponseHeaders(file == null ? 404 : 405, -1);
            exchange.close();
            return;
        }
        String type = file.endsWith(".js") ? "text/javascript" : file.endsWith(".css") ? "text/css" : "text/html";
        byte[] bytes = Files.readAllBytes(Path.of("web",file));
        exchange.getResponseHeaders().set("Content-Type",type + "; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; frame-ancestors 'none'; base-uri 'none'; img-src 'self' data:");
        exchange.sendResponseHeaders(200,bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    static void handle(HttpExchange exchange) throws IOException {
        int status = 200;
        String response;
        try {
            String path = exchange.getRequestURI().getPath().substring(5);
            String method = exchange.getRequestMethod();
            check(method.equals("GET") || method.equals("POST"), 405, "Use GET or POST");
            byte[] body = exchange.getRequestBody().readNBytes(65537);
            check(body.length <= 65536, 413, "Request is too large");
            if (method.equals("POST")) {
                String type = exchange.getRequestHeaders().getFirst("Content-Type");
                check(type != null && type.split(";",2)[0].equalsIgnoreCase("application/x-www-form-urlencoded"),
                    415, "Use form-encoded data");
            }
            Form form = parse(method.equals("GET") ? exchange.getRequestURI().getRawQuery() : new String(body, StandardCharsets.UTF_8));
            try (Connection db = connect()) {
                db.setAutoCommit(false);
                try {
                    if (path.equals("health") && method.equals("GET")) response = "{\"status\":\"ok\"}";
                    else if (path.equals("login") && method.equals("POST")) response = login(db, form);
                    else {
                        String token = exchange.getRequestHeaders().getFirst("Authorization");
                        check(token != null && token.matches("Bearer [a-f0-9]{64}"), 401, "Sign in");
                        String digest = digest(token.substring(7));
                        User user = authenticate(db, digest);
                        response = method.equals("GET") ? read(db, user, path, form) : write(db, user, path, form, digest);
                    }
                    db.commit();
                } catch (Exception failure) {
                    db.rollback();
                    throw failure;
                }
            }
        } catch (Problem failure) {
            status = failure.status;
            response = error(failure.getMessage());
        } catch (IllegalArgumentException | java.time.DateTimeException failure) {
            status = 400;
            response = error("Invalid field value");
        } catch (SQLException failure) {
            String state = failure.getSQLState();
            status = "23505".equals(state) ? 409 : state != null && (state.startsWith("22") || state.startsWith("23")) ? 400 : 503;
            if (status == 503) System.err.println("Database failure: " + state);
            response = error(status == 409 ? "Duplicate record" : status == 400 ? "Invalid data or reference" : "Database unavailable");
        } catch (Exception failure) {
            status = 500;
            System.err.println(failure.getClass().getName());
            response = error("Internal error");
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    static Form parse(String input) {
        Map<String,String> values = new HashMap<>();
        if (input != null && !input.isEmpty()) {
            for (String part : input.split("&")) {
                String[] field = part.split("=", 2);
                check(field.length == 2, 400, "Expected key=value");
                String key = URLDecoder.decode(field[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(field[1], StandardCharsets.UTF_8);
                check(values.putIfAbsent(key, value) == null, 400, "Duplicate field");
            }
        }
        return new Form(values);
    }

    static void validatePassword(String password) {
        check(password.length() >= 12 && password.length() <= 128, 400, "Password must have 12 to 128 characters");
    }

    static String passwordHash(String password) throws GeneralSecurityException {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return HexFormat.of().formatHex(salt) + ":" + HexFormat.of().formatHex(derive(password,salt));
    }

    static byte[] derive(String password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec key = new PBEKeySpec(password.toCharArray(), salt, 600000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(key).getEncoded(); }
        finally { key.clearPassword(); }
    }

    static String digest(String token) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    static String login(Connection db, Form form) throws Exception {
        String password = form.get("password");
        validatePassword(password);
        try (var statement = sql(db, "SELECT id,role,password_hash FROM account WHERE username=?", form.need("username").toLowerCase(Locale.ROOT));
             var row = statement.executeQuery()) {
            boolean found = row.next();
            String[] hash = found ? row.getString(3).split(":") : new String[]{"0".repeat(32), "0".repeat(64)};
            var hex = HexFormat.of();
            boolean matches = MessageDigest.isEqual(derive(password,hex.parseHex(hash[0])),hex.parseHex(hash[1]));
            check(found && matches, 401, "Invalid credentials");
            byte[] random = new byte[32];
            RANDOM.nextBytes(random);
            String token = hex.formatHex(random);
            run(db, "DELETE FROM session_token WHERE account_id=? AND expires<=clock_timestamp()", row.getLong(1));
            run(db, "INSERT INTO session_token VALUES(?,?,clock_timestamp()+interval '12 hours')",digest(token),row.getLong(1));
            return "{\"token\":\"" + token + "\",\"role\":\"" + row.getString(2) + "\"}";
        }
    }

    static User authenticate(Connection db, String digest) throws SQLException {
        try (var statement = sql(db, """
                SELECT a.id,a.role FROM account a JOIN session_token s ON s.account_id=a.id
                WHERE s.token_hash=? AND s.expires>clock_timestamp()
                """, digest); var row = statement.executeQuery()) {
            check(row.next(), 401, "Session expired");
            return new User(row.getLong(1), row.getString(2));
        }
    }

    static PreparedStatement sql(Connection db, String query, Object... values) throws SQLException {
        PreparedStatement statement = db.prepareStatement(query);
        try {
            for (int i=0; i<values.length; i++) statement.setObject(i+1, values[i]);
            return statement;
        } catch (SQLException failure) {
            statement.close();
            throw failure;
        }
    }

    static int run(Connection db, String query, Object... values) throws SQLException {
        try (var statement = sql(db, query, values)) { return statement.executeUpdate(); }
    }

    static String one(Connection db, String query, Object... values) throws SQLException {
        try (var statement = sql(db, query, values); var row = statement.executeQuery()) {
            return row.next() ? Objects.toString(row.getString(1), "") : "";
        }
    }

    static String rows(Connection db, String query, Object... values) throws SQLException {
        return one(db, "SELECT coalesce(json_agg(t),'[]') FROM (" + query + ") t", values);
    }

    static long insert(Connection db, String query, Object... values) throws SQLException {
        return Long.parseLong(one(db, query + " RETURNING id", values));
    }

    static String id(long id) { return "{\"id\":" + id + "}"; }
    static String error(String message) { return "{\"error\":\"" + message + "\"}"; }

    static void audit(Connection db, Long actor, String action, long entity, String detail) throws SQLException {
        run(db, "INSERT INTO audit(actor,action,entity,detail) VALUES(?,?,?,?)",actor,action,entity,detail);
    }

    static String write(Connection db, User user, String path, Form form, String digest) throws Exception {
        return switch (path) {
            case "logout" -> {
                run(db, "DELETE FROM session_token WHERE token_hash=?", digest);
                yield EMPTY;
            }
            case "groups", "subjects", "accounts", "faculty-scopes" -> setup(db,user,path,form);
            case "import-accounts" -> importAccounts(db,user,form);
            case "banks", "questions", "bank-state", "import-questions", "delete-question", "revise-bank" -> author(db,user,path,form);
            case "publish" -> publish(db,user,form);
            case "preview" -> preview(db,user,form);
            case "xlsx-preview" -> workbookPreview(db,user,form);
            case "start" -> start(db,user,form.id("exam"));
            case "answer", "heartbeat", "submit", "next-question" -> attemptAction(db,user,path,form);
            case "release" -> release(db,user,form.id("exam"));
            case "exam-control" -> control(db,user,form);
            case "incident", "resolve-incident" -> incident(db,user,path,form);
            default -> throw new Problem(404,"Unknown endpoint");
        };
    }

    static String setup(Connection db, User user, String path, Form form) throws Exception {
        user.require("ADMIN");
        long entity;
        switch (path) {
            case "groups" -> entity = insert(db, """
                INSERT INTO academic_group(department,academic_session,study_year,semester,section) VALUES(?,?,?,?,?)
                """,form.need("department"),form.need("session"),form.number("year"),form.number("semester"),form.need("section"));
            case "subjects" -> entity = insert(db,"INSERT INTO subject(name) VALUES(?)",form.need("name"));
            case "accounts" -> {
                String password = form.get("password");
                validatePassword(password);
                String role = form.need("role");
                entity = insert(db,"INSERT INTO account(username,password_hash,role,group_id) VALUES(?,?,?,?)",
                    form.need("username").toLowerCase(Locale.ROOT),passwordHash(password),role,
                    role.equals("STUDENT") ? form.id("group") : null);
            }
            default -> {
                entity = form.id("faculty");
                check(run(db, """
                    INSERT INTO faculty_scope SELECT id,?,? FROM account WHERE id=? AND role='FACULTY'
                    ON CONFLICT DO NOTHING
                    """,form.id("group"),form.id("subject"),entity) == 1, 409, "Invalid or duplicate faculty scope");
            }
        }
        audit(db,user.id(),path,entity,"");
        return id(entity);
    }

    static Set<Long> ids(Form form, String key) {
        Set<Long> result = new LinkedHashSet<>();
        for (String raw : form.need(key).split(",",-1)) {
            long value = Long.parseLong(raw.strip());
            check(value > 0 && result.add(value),400,"Invalid ID list");
        }
        return result;
    }

    static String author(Connection db, User user, String path, Form form) throws SQLException {
        user.require("ADMIN","FACULTY");
        boolean admin = user.role().equals("ADMIN");
        if (path.equals("banks")) {
            long subject = form.id("subject");
            long bank = insert(db,"INSERT INTO bank(owner_id,subject_id,title) VALUES(?,?,?)",user.id(),subject,form.need("title"));
            for (long group : ids(form,"groups")) {
                check(admin || !one(db,"SELECT 1 FROM faculty_scope WHERE faculty_id=? AND group_id=? AND subject_id=?",
                    user.id(),group,subject).isEmpty(),403,"Group outside faculty scope");
                run(db,"INSERT INTO bank_scope VALUES(?,?)",bank,group);
            }
            audit(db,user.id(),"BANK_CREATED",bank,"");
            return id(bank);
        }
        long bank = form.id("bank");
        String state = one(db,"SELECT state FROM bank WHERE id=? AND (? OR owner_id=?) FOR UPDATE",bank,admin,user.id());
        check(!state.isEmpty(),404,"Bank not found");
        if (path.equals("revise-bank")) {
            long revision = insert(db,"INSERT INTO bank(owner_id,subject_id,title) SELECT owner_id,subject_id,? FROM bank WHERE id=?",
                form.need("title"),bank);
            run(db,"INSERT INTO bank_scope SELECT ?,group_id FROM bank_scope WHERE bank_id=?",revision,bank);
            run(db,"""
                INSERT INTO question(bank_id,prompt,options,correct,explanation,topic,difficulty,marks,penalty,image)
                SELECT ?,prompt,options,correct,explanation,topic,difficulty,marks,penalty,image FROM question WHERE bank_id=?
                """,revision,bank);
            audit(db,user.id(),"BANK_REVISED",revision,Long.toString(bank));
            return id(revision);
        }
        if (path.equals("delete-question")) {
            check(state.equals("DRAFT"),409,"Bank must be a draft");
            long question = form.id("question");
            check(run(db,"DELETE FROM question WHERE id=? AND bank_id=?",question,bank)==1,404,"Question not found");
            audit(db,user.id(),"QUESTION_DELETED",question,"");
            return id(question);
        }
        if (path.equals("import-questions")) return importQuestions(db,user,bank,state,form);
        if (path.equals("questions")) {
            check(state.equals("DRAFT"),409,"Bank must be a draft");
            String[] options = {form.need("a"),form.need("b"),form.need("c"),form.need("d")};
            check(new HashSet<>(Arrays.asList(options)).size()==4,400,"Options must be distinct");
            Array array = db.createArrayOf("text", options);
            try {
                long question = insert(db,"""
                    INSERT INTO question(bank_id,prompt,options,correct,explanation,topic,difficulty,marks,penalty,image)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """,bank,form.need("prompt"),array,form.number("correct"),form.get("explanation"),
                    form.need("topic"),form.need("difficulty"),form.decimal("marks"),form.decimal("penalty"),questionImage(form));
                audit(db,user.id(),"QUESTION_CREATED",question,"");
                return id(question);
            } finally { array.free(); }
        }
        String next = form.need("state");
        boolean allowed = switch (next) {
            case "SUBMITTED" -> state.equals("DRAFT") && !one(db,"SELECT 1 FROM question WHERE bank_id=? LIMIT 1",bank).isEmpty();
            case "APPROVED","REJECTED" -> admin && state.equals("SUBMITTED");
            case "DRAFT" -> state.equals("REJECTED");
            case "ARCHIVED" -> admin && Set.of("APPROVED","REJECTED").contains(state);
            default -> false;
        };
        check(allowed,409,"Invalid bank transition");
        run(db,"UPDATE bank SET state=? WHERE id=?",next,bank);
        audit(db,user.id(),"BANK_" + next,bank,"");
        return id(bank);
    }

    static String publish(Connection db, User user, Form form) throws SQLException {
        user.require("ADMIN");
        long bank = form.id("bank");
        String mode = form.need("mode");
        String state = one(db,"SELECT state FROM bank WHERE id=? FOR UPDATE",bank);
        check(state.equals("APPROVED"),409,"An approved bank is required");
        int limit = form.number("attempts");
        check(!mode.equals("OFFICIAL") || limit==1,400,"Official exams allow one attempt");
        OffsetDateTime opens = OffsetDateTime.parse(form.need("opens"));
        OffsetDateTime closes = OffsetDateTime.parse(form.need("closes"));
        BigDecimal pass = form.decimal("pass");
        String navigation = form.get("navigation").isBlank() ? "FREE" : form.get("navigation");
        check(Set.of("FREE","FORWARD").contains(navigation),400,"Invalid navigation rule");
        String feedback = mode.equals("OFFICIAL") ? "DELAYED" :
            form.get("feedback").isBlank() ? "SCORE" : form.get("feedback");
        check(Set.of("DELAYED","SCORE","EXPLANATIONS").contains(feedback),400,"Invalid practice feedback rule");
        long exam = insert(db,"""
            INSERT INTO exam(title,instructions,bank_id,mode,duration,attempt_limit,opens,closes,pass_marks,navigation,practice_feedback)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """,form.need("title"),form.get("instructions"),bank,mode,form.number("duration"),limit,opens,closes,pass,navigation,feedback);
        int position = 0;
        for (String difficulty : List.of("EASY","MEDIUM","HARD")) {
            int count = form.number(difficulty.toLowerCase(Locale.ROOT));
            check(count>=0 && position+((long)count)<=500,400,"Question count must be 1 to 500");
            int selected = run(db,"""
                INSERT INTO exam_question(exam_id,position,prompt,options,correct,explanation,topic,difficulty,marks,penalty,image)
                SELECT ?,CAST(? + row_number() OVER (ORDER BY random()) AS int),
                    prompt,options,correct,explanation,topic,difficulty,marks,penalty,image
                FROM (SELECT * FROM question WHERE bank_id=? AND difficulty=? ORDER BY random() LIMIT ?) q
                """,exam,position,bank,difficulty,count);
            check(selected==count,409,"Not enough questions for the difficulty mix");
            position += count;
        }
        check(position>0,400,"Select at least one question");
        check(pass.compareTo(new BigDecimal(one(db,"SELECT sum(marks) FROM exam_question WHERE exam_id=?",exam)))<=0,
            400,"Passing marks exceed total marks");
        for (long group : ids(form,"groups")) {
            check(!one(db,"SELECT 1 FROM bank_scope WHERE bank_id=? AND group_id=?",bank,group).isEmpty(),403,"Group outside bank scope");
            run(db,"INSERT INTO assignment SELECT ?,id FROM account WHERE group_id=? AND role='STUDENT'",exam,group);
        }
        if (!form.get("students").isBlank()) {
            Set<Long> selected = ids(form,"students");
            Array ids = db.createArrayOf("bigint",selected.toArray(Long[]::new));
            try {
                check(Integer.parseInt(one(db,"SELECT count(*) FROM assignment WHERE exam_id=? AND student_id=ANY(?)",exam,ids))
                    ==selected.size(),400,"Student list contains an ineligible account");
                run(db,"DELETE FROM assignment WHERE exam_id=? AND NOT (student_id=ANY(?))",exam,ids);
            } finally { ids.free(); }
        }
        check(!one(db,"SELECT 1 FROM assignment WHERE exam_id=? LIMIT 1",exam).isEmpty(),409,"No eligible students");
        audit(db,user.id(),"EXAM_PUBLISHED",exam,"");
        return id(exam);
    }

    static String start(Connection db, User user, long exam) throws SQLException {
        user.require("STUDENT");
        lockExam(db,exam);
        check(!one(db,"SELECT 1 FROM assignment WHERE exam_id=? AND student_id=? FOR UPDATE",exam,user.id()).isEmpty(),
            404,"Exam not assigned");
        check(!paused(db,exam),409,"Exam is paused");
        String active = one(db,"SELECT id FROM attempt WHERE exam_id=? AND student_id=? AND submitted IS NULL FOR UPDATE",exam,user.id());
        if (!active.isEmpty()) {
            long attempt = Long.parseLong(active);
            if (!finishIfDue(db,attempt)) return id(attempt);
        }
        // Return the existing attempt when a start retry hits the attempt limit.
        String previous = one(db,"""
            SELECT max(a.id) FROM attempt a JOIN exam e ON e.id=a.exam_id
            WHERE a.exam_id=? AND a.student_id=? GROUP BY e.attempt_limit HAVING count(*)>=e.attempt_limit
            """,exam,user.id());
        if (!previous.isEmpty()) return id(Long.parseLong(previous));
        String created = one(db,"""
            WITH now AS (SELECT clock_timestamp() AS t)
            INSERT INTO attempt(exam_id,student_id,started,deadline,last_seen)
            SELECT e.id,?,t,least(e.closes,t+e.duration*interval '1 second'),t
            FROM exam e CROSS JOIN now WHERE e.id=? AND t>=e.opens AND t<e.closes RETURNING id
            """,user.id(),exam);
        if (created.isEmpty()) {
            if (!active.isEmpty()) return id(Long.parseLong(active));
            throw new Problem(409,"Exam is outside its availability window");
        }
        long attempt = Long.parseLong(created);
        audit(db,user.id(),"ATTEMPT_STARTED",attempt,"");
        return id(attempt);
    }

    static boolean finishIfDue(Connection db, long attempt) throws SQLException {
        boolean due = one(db,"SELECT (deadline<=clock_timestamp())::text FROM attempt WHERE id=?",attempt).equals("true");
        if (due) finish(db,attempt,null,"DEADLINE");
        return due;
    }

    static void finish(Connection db, long attempt, Long actor, String reason) throws SQLException {
        int changed = run(db,"""
            UPDATE attempt a SET submitted=clock_timestamp(),reason=?,score=(
                SELECT sum(CASE WHEN b.choice=q.correct THEN q.marks WHEN b.choice IS NOT NULL THEN -q.penalty ELSE 0 END)
                FROM exam_question q LEFT JOIN answer b ON b.attempt_id=a.id AND b.position=q.position
                WHERE q.exam_id=a.exam_id
            ) WHERE a.id=? AND a.submitted IS NULL
            """,reason,attempt);
        if (changed==1) audit(db,actor,"ATTEMPT_SUBMITTED",attempt,reason);
    }

    static void expire() {
        try (Connection db = connect()) {
            db.setAutoCommit(false);
            try (var exams = sql(db,"""
                SELECT id FROM exam e WHERE paused_at IS NULL AND EXISTS(
                    SELECT 1 FROM attempt a WHERE a.exam_id=e.id AND a.submitted IS NULL AND a.deadline<=clock_timestamp()
                ) ORDER BY id FOR SHARE SKIP LOCKED
                """); var examRows = exams.executeQuery()) {
                while (examRows.next()) {
                    try (var statement = sql(db,"""
                        SELECT id FROM attempt WHERE exam_id=? AND submitted IS NULL AND deadline<=clock_timestamp()
                        ORDER BY deadline LIMIT 100 FOR UPDATE SKIP LOCKED
                        """,examRows.getLong(1)); var result = statement.executeQuery()) {
                        while (result.next()) finish(db,result.getLong(1),null,"DEADLINE");
                    }
                }
            }
            db.commit();
        } catch (SQLException failure) {
            System.err.println("Expiry worker database failure: " + failure.getSQLState());
        }
    }

    static String attemptAction(Connection db, User user, String path, Form form) throws SQLException {
        user.require("STUDENT");
        long attempt = form.id("attempt");
        long examId = lockAttempt(db,user,attempt);
        if (paused(db,examId) && one(db,"SELECT submitted FROM attempt WHERE id=?",attempt).isEmpty()) {
            if (path.equals("heartbeat")) return "{\"status\":\"PAUSED\"}";
            throw new Problem(423,"Exam paused. Pending answers will sync after it resumes.");
        }
        boolean finished = !one(db,"SELECT submitted FROM attempt WHERE id=?",attempt).isEmpty() || finishIfDue(db,attempt);
        if (path.equals("submit")) {
            if (!finished) finish(db,attempt,user.id(),"MANUAL");
            return "{\"status\":\"SUBMITTED\"}";
        }
        if (finished) return "{\"status\":\"SUBMITTED\"}";
        if (path.equals("heartbeat")) {
            int pending = form.number("pending");
            check(pending>=0 && pending<=500,400,"Invalid pending count");
            run(db,"UPDATE attempt SET last_seen=clock_timestamp(),pending=? WHERE id=?",pending,attempt);
            return "{\"status\":\"ACTIVE\"}";
        }
        if (path.equals("next-question")) {
            int position = form.number("position");
            String next = one(db,"""
                UPDATE attempt a SET current_position=a.current_position+1,last_seen=clock_timestamp()
                FROM exam e WHERE a.id=? AND e.id=a.exam_id AND e.navigation='FORWARD'
                AND a.current_position=? AND EXISTS(SELECT 1 FROM exam_question q WHERE q.exam_id=e.id AND q.position=?+1)
                RETURNING a.current_position
                """,attempt,position,position);
            if (next.isEmpty()) {
                next = one(db,"SELECT current_position FROM attempt WHERE id=?",attempt);
                check(Integer.parseInt(next)==((long)position)+1,409,"Cannot advance from that position");
            }
            return "{\"position\":" + next + "}";
        }
        int position = form.number("position");
        check(one(db,"""
            SELECT (e.navigation='FREE' OR a.current_position=?)::text FROM attempt a JOIN exam e ON e.id=a.exam_id WHERE a.id=?
            """,position,attempt).equals("true"),409,"Question is unavailable under the navigation rule");
        long version = form.id("version");
        int choice = form.number("choice");
        String review = form.need("review");
        check(choice>=0 && choice<=4 && Set.of("true","false").contains(review),400,"Invalid choice or review flag");
        Integer selected = choice==0 ? null : choice;
        boolean marked = Boolean.parseBoolean(review);

        check(!one(db,"SELECT 1 FROM exam_question WHERE exam_id=? AND position=?",examId,position).isEmpty(),404,"Question not found");
        int saved = run(db,"""
            INSERT INTO answer(attempt_id,exam_id,position,choice,review,version) VALUES(?,?,?,?,?,?)
            ON CONFLICT(attempt_id,position) DO UPDATE SET choice=excluded.choice,review=excluded.review,version=excluded.version
            WHERE answer.version<excluded.version
            """,attempt,examId,position,selected,marked,version);
        if (saved==0) {
            check(!one(db,"""
                SELECT 1 FROM answer WHERE attempt_id=? AND position=? AND version=?
                AND choice IS NOT DISTINCT FROM CAST(? AS int) AND review=?
                """,attempt,position,version,selected,marked).isEmpty(),409,"Stale answer version");
        }
        run(db,"UPDATE attempt SET last_seen=clock_timestamp() WHERE id=?",attempt);
        return "{\"status\":\"SAVED\",\"version\":" + version + "}";
    }

    static String release(Connection db, User user, long exam) throws SQLException {
        user.require("ADMIN");
        String ready = one(db,"SELECT (paused_at IS NULL AND closes<=clock_timestamp())::text FROM exam WHERE id=? FOR UPDATE",exam);
        check(!ready.isEmpty(),404,"Exam not found");
        check(ready.equals("true") && one(db,"SELECT 1 FROM attempt WHERE exam_id=? AND submitted IS NULL LIMIT 1",exam).isEmpty(),
            409,"Wait until the exam window closes and all attempts are scored");
        if (run(db,"UPDATE exam SET released=true WHERE id=? AND NOT released",exam)==1)
            audit(db,user.id(),"RESULTS_RELEASED",exam,"");
        return id(exam);
    }

    static String read(Connection db, User user, String path, Form form) throws SQLException {
        return switch (path) {
            case "groups", "subjects", "accounts", "faculty-scopes", "audit" -> catalog(db,user,path);
            case "banks", "questions", "my-scopes" -> bankRead(db,user,path,form);
            case "exams" -> {
                user.require("STUDENT","ADMIN","OBSERVER","FACULTY");
                yield rows(db,"""
                    SELECT e.id,e.title,e.instructions,e.mode,e.duration,e.attempt_limit,e.opens,e.closes,e.pass_marks,e.released,e.paused_at,e.navigation,
                           (SELECT count(*) FROM exam_question q WHERE q.exam_id=e.id) AS questions,
                           (SELECT sum(q.marks) FROM exam_question q WHERE q.exam_id=e.id) AS total_marks
                    FROM exam e WHERE ? OR (? AND EXISTS(SELECT 1 FROM assignment a WHERE a.exam_id=e.id AND a.student_id=?))
                    OR (? AND
                    """ + FACULTY_EXAM_SCOPE + ") ORDER BY e.id", Set.of("ADMIN","OBSERVER").contains(user.role()),
                    user.role().equals("STUDENT"),user.id(),user.role().equals("FACULTY"),user.id());
            }
            case "attempts" -> {
                user.require("STUDENT");
                yield rows(db,"SELECT id,started,deadline,submitted,reason FROM attempt WHERE student_id=? AND exam_id=? ORDER BY id",
                    user.id(),form.id("exam"));
            }
            case "attempt" -> attemptRead(db,user,form.id("attempt"));
            case "monitor" -> monitor(db,user,form.id("exam"));
            case "incidents" -> incidents(db,user,form.id("exam"));
            case "analytics" -> analytics(db,user,form.id("exam"));
            case "xlsx-report" -> workbookReport(db,user,form.id("exam"));
            default -> throw new Problem(404,"Unknown endpoint");
        };
    }

    static String catalog(Connection db, User user, String path) throws SQLException {
        user.require("ADMIN");
        return switch (path) {
            case "groups" -> rows(db,"SELECT * FROM academic_group ORDER BY id");
            case "subjects" -> rows(db,"SELECT * FROM subject ORDER BY id");
            case "accounts" -> rows(db,"SELECT id,username,role,group_id FROM account ORDER BY id");
            case "faculty-scopes" -> rows(db,"SELECT * FROM faculty_scope ORDER BY faculty_id,group_id,subject_id");
            default -> rows(db,"SELECT * FROM audit ORDER BY id DESC LIMIT 200");
        };
    }

    static String bankRead(Connection db, User user, String path, Form form) throws SQLException {
        user.require("ADMIN","FACULTY");
        boolean admin = user.role().equals("ADMIN");
        if (path.equals("my-scopes")) {
            user.require("FACULTY");
            return rows(db,"""
                SELECT g.*,s.id AS subject_id,s.name AS subject
                FROM faculty_scope f JOIN academic_group g ON g.id=f.group_id
                JOIN subject s ON s.id=f.subject_id WHERE f.faculty_id=? ORDER BY g.id,s.id
                """,user.id());
        }
        if (path.equals("banks")) {
            return rows(db,"""
                SELECT b.*,array(SELECT s.group_id FROM bank_scope s WHERE s.bank_id=b.id ORDER BY s.group_id) AS groups
                FROM bank b WHERE ? OR b.owner_id=? ORDER BY id
                """,admin,user.id());
        }
        return rows(db,"""
            SELECT q.* FROM question q JOIN bank b ON b.id=q.bank_id WHERE b.id=? AND (? OR b.owner_id=?) ORDER BY q.id
            """,form.id("bank"),admin,user.id());
    }

    static String attemptRead(Connection db, User user, long attempt) throws SQLException {
        user.require("STUDENT");
        long exam = lockAttempt(db,user,attempt);
        if (!paused(db,exam)) finishIfDue(db,attempt);
        return one(db,"""
            SELECT json_build_object(
                'id',a.id,'exam',e.id,'title',e.title,'deadline',a.deadline,'serverTime',clock_timestamp(),
                'submitted',a.submitted,'reason',a.reason,'paused',e.paused_at IS NOT NULL,
                'navigation',e.navigation,'currentPosition',a.current_position,
                'score',CASE WHEN e.released OR (e.mode='PRACTICE' AND e.practice_feedback<>'DELAYED') THEN a.score END,
                'passed',CASE WHEN e.released OR (e.mode='PRACTICE' AND e.practice_feedback<>'DELAYED') THEN a.score>=e.pass_marks END,
                'questions',(SELECT coalesce(json_agg(q),'[]') FROM (
                    SELECT x.position,
                           CASE WHEN e.navigation='FREE' OR a.current_position=x.position OR a.submitted IS NOT NULL THEN x.prompt END AS prompt,
                           CASE WHEN e.navigation='FREE' OR a.current_position=x.position OR a.submitted IS NOT NULL THEN x.options END AS options,
                           x.marks,x.penalty,
                           CASE WHEN e.navigation='FREE' OR a.current_position=x.position OR a.submitted IS NOT NULL THEN x.image END AS image,
                           b.choice,b.version,b.review,
                           CASE WHEN (e.released OR (e.mode='PRACTICE' AND e.practice_feedback='EXPLANATIONS')) AND a.submitted IS NOT NULL THEN x.correct END AS correct,
                           CASE WHEN (e.released OR (e.mode='PRACTICE' AND e.practice_feedback='EXPLANATIONS')) AND a.submitted IS NOT NULL THEN x.explanation END AS explanation
                    FROM exam_question x LEFT JOIN answer b ON b.attempt_id=a.id AND b.position=x.position
                    WHERE x.exam_id=e.id ORDER BY x.position
                ) q)
            ) FROM attempt a JOIN exam e ON e.id=a.exam_id WHERE a.id=?
            """,attempt);
    }

    static String monitor(Connection db, User user, long exam) throws SQLException {
        requireReportScope(db,user,exam);
        return rows(db,"""
            SELECT s.student_id,u.username,u.group_id,a.id AS attempt_id,
                CASE WHEN a.id IS NULL THEN 'NOT_STARTED' WHEN a.submitted IS NOT NULL THEN 'SUBMITTED'
                     WHEN e.paused_at IS NOT NULL THEN 'PAUSED' WHEN a.last_seen<clock_timestamp()-interval '45 seconds' THEN 'DISCONNECTED' ELSE 'ACTIVE' END AS status,
                a.deadline, greatest(0,extract(epoch FROM a.deadline-coalesce(e.paused_at,clock_timestamp())))::int AS remaining_seconds,
                a.pending AS reported_pending,a.score,a.score>=e.pass_marks AS passed,
                (SELECT count(*) FROM answer b WHERE b.attempt_id=a.id AND b.choice IS NOT NULL) AS answered,
                (SELECT count(*) FROM exam_question q WHERE q.exam_id=e.id) AS total
            FROM assignment s JOIN account u ON u.id=s.student_id JOIN exam e ON e.id=s.exam_id
            LEFT JOIN LATERAL (SELECT * FROM attempt t WHERE t.exam_id=s.exam_id AND t.student_id=s.student_id
                               ORDER BY id DESC LIMIT 1) a ON true
            WHERE s.exam_id=? ORDER BY u.username
            """,exam);
    }

    static void lockExam(Connection db, long exam) throws SQLException {
        check(!one(db,"SELECT id FROM exam WHERE id=? FOR SHARE",exam).isEmpty(),404,"Exam not found");
    }

    static boolean paused(Connection db, long exam) throws SQLException {
        return !one(db,"SELECT paused_at FROM exam WHERE id=?",exam).isEmpty();
    }

    static long lockAttempt(Connection db, User user, long attempt) throws SQLException {
        String exam = one(db,"SELECT exam_id FROM attempt WHERE id=? AND student_id=?",attempt,user.id());
        check(!exam.isEmpty(),404,"Attempt not found");
        long examId = Long.parseLong(exam);
        lockExam(db,examId);
        one(db,"SELECT id FROM attempt WHERE id=? FOR UPDATE",attempt);
        return examId;
    }

    static String control(Connection db, User user, Form form) throws SQLException {
        user.require("ADMIN");
        long exam = form.id("exam");
        String command = form.need("command");
        String reason = form.need("reason");
        check(!one(db,"SELECT id FROM exam WHERE id=? AND NOT released FOR UPDATE",exam).isEmpty(),409,"Exam unavailable for overrides");
        boolean paused = paused(db,exam);
        switch (command) {
            case "START" -> check(run(db,"UPDATE exam SET opens=clock_timestamp() WHERE id=? AND opens>clock_timestamp()",exam)==1,
                409,"Only a scheduled exam can open early");
            case "PAUSE" -> {
                check(!paused && run(db,"""
                    UPDATE exam SET paused_at=clock_timestamp()
                    WHERE id=? AND opens<=clock_timestamp() AND closes>clock_timestamp()
                    """,exam)==1,409,"Only an open exam can be paused");
                finishDueForControl(db,exam);
            }
            case "RESUME" -> {
                check(paused,409,"Exam is not paused");
                OffsetDateTime resume = OffsetDateTime.parse(one(db,"SELECT to_json(clock_timestamp()) #>> '{}'"));
                run(db,"""
                    UPDATE attempt a SET deadline=a.deadline+(? - e.paused_at)
                    FROM exam e WHERE e.id=? AND a.exam_id=e.id AND a.submitted IS NULL
                    """,resume,exam);
                run(db,"UPDATE exam SET closes=closes+(? - paused_at),paused_at=NULL WHERE id=?",resume,exam);
            }
            case "EXTEND" -> {
                int seconds = form.number("seconds");
                check(seconds>0 && seconds<=14400,400,"Extension must be between 1 and 14400 seconds");
                check(!paused && one(db,"SELECT (closes>clock_timestamp())::text FROM exam WHERE id=?",exam).equals("true"),
                    409,"Exam must have an open availability window");
                finishDueForControl(db,exam);
                run(db,"UPDATE exam SET closes=closes+?*interval '1 second' WHERE id=?",seconds,exam);
                run(db,"UPDATE attempt SET deadline=deadline+?*interval '1 second' WHERE exam_id=? AND submitted IS NULL",seconds,exam);
            }
            case "CLOSE" -> {
                check(run(db,"""
                    UPDATE exam SET closes=clock_timestamp(),paused_at=NULL
                    WHERE id=? AND opens<clock_timestamp() AND closes>clock_timestamp()
                    """,exam)==1 || paused,409,"Exam is not open");
                if (paused) run(db,"UPDATE exam SET closes=clock_timestamp(),paused_at=NULL WHERE id=?",exam);
                try (var statement = sql(db,"SELECT id FROM attempt WHERE exam_id=? AND submitted IS NULL FOR UPDATE",exam);
                     var rows = statement.executeQuery()) {
                    while (rows.next()) finish(db,rows.getLong(1),user.id(),"ADMIN_CLOSE");
                }
            }
            default -> throw new Problem(400,"Unknown exam command");
        }
        audit(db,user.id(),"EXAM_" + command,exam,reason);
        return id(exam);
    }

    static void finishDueForControl(Connection db,long exam) throws SQLException {
        try (var statement = sql(db,"""
            SELECT a.id FROM attempt a JOIN exam e ON e.id=a.exam_id
            WHERE e.id=? AND a.submitted IS NULL AND a.deadline<=coalesce(e.paused_at,clock_timestamp())
            FOR UPDATE OF a
            """,exam); var rows = statement.executeQuery()) {
            while (rows.next()) finish(db,rows.getLong(1),null,"DEADLINE");
        }
    }

    static String incident(Connection db, User user, String path, Form form) throws SQLException {
        if (path.equals("incident")) {
            user.require("STUDENT");
            long attempt = form.id("attempt");
            lockAttempt(db,user,attempt);
            long id = insert(db,"INSERT INTO incident(attempt_id,detail) VALUES(?,?)",attempt,form.need("detail"));
            audit(db,user.id(),"INCIDENT_REPORTED",id,"");
            return id(id);
        }
        user.require("ADMIN");
        long id = form.id("incident");
        String detail = form.need("detail");
        check(run(db,"UPDATE incident SET resolution=?,resolved=clock_timestamp() WHERE id=? AND resolved IS NULL",detail,id)==1,
            409,"Incident unavailable or already resolved");
        audit(db,user.id(),"INCIDENT_RESOLVED",id,detail);
        return id(id);
    }

    static String incidents(Connection db, User user, long exam) throws SQLException {
        user.require("ADMIN","OBSERVER");
        return rows(db,"""
            SELECT i.*,u.username FROM incident i JOIN attempt a ON a.id=i.attempt_id
            JOIN account u ON u.id=a.student_id WHERE a.exam_id=? ORDER BY i.id DESC
            """,exam);
    }

    static String analytics(Connection db, User user, long exam) throws SQLException {
        requireReportScope(db,user,exam);
        String groups = rows(db,"""
            SELECT g.id,g.department,g.semester,g.section,count(*) AS assigned,count(a.submitted) AS completed,
                round(avg(a.score),2) AS average,count(*) FILTER (WHERE a.score>=e.pass_marks) AS passed
            FROM assignment s JOIN account u ON u.id=s.student_id JOIN academic_group g ON g.id=u.group_id
            JOIN exam e ON e.id=s.exam_id
            LEFT JOIN LATERAL (SELECT submitted,score FROM attempt a
                WHERE a.exam_id=s.exam_id AND a.student_id=s.student_id ORDER BY a.id DESC LIMIT 1) a ON true
            WHERE s.exam_id=? GROUP BY g.id ORDER BY g.department,g.semester,g.section
            """,exam);
        String questions = rows(db,"""
            SELECT q.position,q.topic,q.difficulty,count(a.id) AS submitted,
                count(b.choice) AS answered,count(*) FILTER (WHERE b.choice=q.correct) AS correct
            FROM exam_question q LEFT JOIN attempt a ON a.exam_id=q.exam_id AND a.submitted IS NOT NULL
            LEFT JOIN answer b ON b.attempt_id=a.id AND b.position=q.position
            WHERE q.exam_id=? GROUP BY q.exam_id,q.position ORDER BY q.position
            """,exam);
        return "{\"groups\":" + groups + ",\"questions\":" + questions + "}";
    }

    static String importQuestions(Connection db, User user, long bank, String state, Form form) throws SQLException {
        String payload = form.get("rows");
        check(!payload.isBlank() && payload.length()<=48000,400,"Import must contain 1 to 48000 characters");
        String fingerprint = one(db,"SELECT md5(CAST(? AS text))",payload);
        String previous = one(db,"SELECT imported FROM import_receipt WHERE bank_id=? AND fingerprint=?",bank,fingerprint);
        if (!previous.isEmpty()) return "{\"count\":" + previous + "}";
        check(state.equals("DRAFT"),409,"Bank must be a draft");
        try (var statement = sql(db,"""
            SELECT * FROM json_to_recordset(CAST(? AS json)) AS r(
                prompt text,a text,b text,c text,d text,correct text,explanation text,topic text,difficulty text,marks text,penalty text
            )
            """,payload); var rows = statement.executeQuery()) {
            int count = 0;
            while (rows.next()) {
                count++;
                Map<String,String> fields = new HashMap<>();
                fields.put("bank",Long.toString(bank));
                for (String name : List.of("prompt","a","b","c","d","correct","explanation","topic","difficulty","marks","penalty"))
                    fields.put(name,Objects.toString(rows.getString(name),""));
                try {
                    author(db,user,"questions",new Form(fields));
                } catch (Problem invalid) {
                    throw new Problem(invalid.status,"Import row " + count + ": " + invalid.getMessage());
                } catch (SQLException invalid) {
                    if ("23505".equals(invalid.getSQLState())) throw new Problem(409,"Import row " + count + ": duplicate question");
                    if (invalid.getSQLState()!=null && invalid.getSQLState().startsWith("23"))
                        throw new Problem(400,"Import row " + count + ": invalid answer, difficulty, marks or penalty");
                    throw invalid;
                } catch (IllegalArgumentException invalid) {
                    throw new Problem(400,"Import row " + count + ": invalid field value");
                }
            }
            check(count>0,400,"Import must contain at least one question");
            run(db,"INSERT INTO import_receipt VALUES(?,?,?)",bank,fingerprint,count);
            audit(db,user.id(),"QUESTIONS_IMPORTED",bank,Integer.toString(count));
            return "{\"count\":" + count + "}";
        }
    }
    static String questionImage(Form form) {
        String value = form.get("image");
        if (value.isEmpty()) return null;
        check(value.length()<=42000 && (value.startsWith("data:image/png;base64,") || value.startsWith("data:image/jpeg;base64,")),
            400,"Use a PNG or JPEG image under 30 KiB");
        byte[] bytes = Base64.getDecoder().decode(value.substring(value.indexOf(',')+1));
        byte[] png = {(byte)137,80,78,71,13,10,26,10};
        boolean valid = value.startsWith("data:image/png")
            ? bytes.length>=8 && Arrays.equals(Arrays.copyOf(bytes,8),png)
            : bytes.length>=4 && bytes[0]==(byte)255 && bytes[1]==(byte)216 && bytes[2]==(byte)255;
        check(valid && bytes.length<=30720,400,"Invalid image file");
        return value;
    }
    static String preview(Connection db, User user, Form form) throws SQLException {
        Savepoint savepoint = db.setSavepoint();
        String published = publish(db,user,form);
        long exam = Long.parseLong(published.substring(6,published.length()-1));
        String report = one(db,"""
            SELECT json_build_object('students',(SELECT count(*) FROM assignment WHERE exam_id=?),
                'questions',count(*),'marks',sum(marks),'sample',json_agg(json_build_object('prompt',prompt,'options',options) ORDER BY position))
            FROM exam_question WHERE exam_id=?
            """,exam,exam);
        db.rollback(savepoint);
        return report;
    }

    static String workbookPreview(Connection db,User user,Form form) throws SQLException {
        user.require("ADMIN","FACULTY");
        List<List<String>> data;
        try {
            byte[] bytes = Base64.getDecoder().decode(form.get("file"));
            check(bytes.length<=42000,400,"XLSX file too large");
            data = Workbook.read(bytes);
        } catch (Exception invalid) {
            throw new Problem(400,"Invalid XLSX. Use the first worksheet and the question template columns, with values only.");
        }
        List<String> rows = new ArrayList<>();
        for (List<String> row : data) {
            Array values = db.createArrayOf("text",row.toArray(String[]::new));
            try { rows.add(one(db,"SELECT to_json(CAST(? AS text[]))",values)); }
            finally { values.free(); }
        }
        return "[" + String.join(",",rows) + "]";
    }

    static String workbookReport(Connection db,User user,long exam) throws SQLException {
        requireReportScope(db,user,exam);
        List<List<String>> data = new ArrayList<>();
        data.add(List.of("Student","Group","Attempt","Submitted","Score","Passed"));
        try (var statement = sql(db,"""
            SELECT u.username,u.group_id,a.id,a.submitted,a.score,a.score>=e.pass_marks
            FROM assignment s JOIN account u ON u.id=s.student_id JOIN exam e ON e.id=s.exam_id
            LEFT JOIN attempt a ON a.exam_id=s.exam_id AND a.student_id=s.student_id
            WHERE s.exam_id=? ORDER BY u.username,a.id
            """,exam); var rows = statement.executeQuery()) {
            while (rows.next()) {
                List<String> record = new ArrayList<>();
                for (int col=1;col<=6;col++) record.add(Objects.toString(rows.getString(col),""));
                data.add(record);
            }
        }
        try {
            return "{\"file\":\"" + Base64.getEncoder().encodeToString(Workbook.write(data)) + "\"}";
        } catch (IOException failure) {
            throw new Problem(500,"Excel export failed");
        }
    }
    static final String FACULTY_EXAM_SCOPE = """
        NOT EXISTS (
          SELECT 1 FROM assignment a JOIN account u ON u.id=a.student_id JOIN bank b ON b.id=e.bank_id
          WHERE a.exam_id=e.id AND NOT EXISTS (
            SELECT 1 FROM faculty_scope f WHERE f.faculty_id=? AND f.group_id=u.group_id AND f.subject_id=b.subject_id
          )
        )
        """;

    static void requireReportScope(Connection db,User user,long exam) throws SQLException {
        user.require("ADMIN","OBSERVER","FACULTY");
        if (user.role().equals("FACULTY"))
            check(!one(db,"SELECT 1 FROM exam e WHERE e.id=? AND " + FACULTY_EXAM_SCOPE,exam,user.id()).isEmpty(),
                403,"Exam outside faculty scope");
    }
    static String importAccounts(Connection db,User user,Form form) throws Exception {
        user.require("ADMIN");
        String payload = form.get("rows");
        check(!payload.isBlank() && payload.length()<=32000,400,"Invalid roster size");
        try (var statement = sql(db,"""
            SELECT * FROM json_to_recordset(CAST(? AS json)) AS r(username text,password text,role text,"group" text)
            """,payload); var rows = statement.executeQuery()) {
            int count=0;
            while (rows.next()) {
                check(++count<=100,400,"Import at most 100 accounts per batch");
                var fields = new HashMap<String,String>();
                for (String key : List.of("username","password","role","group"))
                    fields.put(key,Objects.toString(rows.getString(key),""));
                check(Set.of("STUDENT","FACULTY").contains(fields.get("role")),400,
                    "Roster row " + count + ": role must be STUDENT or FACULTY");
                try {
                    setup(db,user,"accounts",new Form(fields));
                } catch (Problem invalid) {
                    throw new Problem(invalid.status,"Roster row " + count + ": " + invalid.getMessage());
                } catch (IllegalArgumentException invalid) {
                    throw new Problem(400,"Roster row " + count + ": invalid field value");
                } catch (SQLException invalid) {
                    if ("23505".equals(invalid.getSQLState())) throw new Problem(409,"Roster row " + count + ": duplicate username");
                    if ("23503".equals(invalid.getSQLState())) throw new Problem(400,"Roster row " + count + ": academic group not found");
                    throw invalid;
                }
            }
            check(count>0,400,"Roster must contain an account");
            return "{\"count\":" + count + "}";
        }
    }
}
