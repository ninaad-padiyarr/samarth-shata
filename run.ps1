param([switch]$BuildOnly)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    javac --release 17 -d build CampusExam.java Workbook.java
    if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
    if ($BuildOnly) { return }
    $driver = $env:PG_JDBC_JAR
    if (-not $driver) { $driver = 'lib/postgresql.jar' }
    if (-not (Test-Path -LiteralPath $driver -PathType Leaf)) {
        throw 'Place the PostgreSQL JDBC jar at lib/postgresql.jar or set PG_JDBC_JAR.'
    }
    java -cp "build;$driver" CampusExam
    if ($LASTEXITCODE -ne 0) { throw 'CampusExam stopped with an error' }
} finally {
    Pop-Location
}
