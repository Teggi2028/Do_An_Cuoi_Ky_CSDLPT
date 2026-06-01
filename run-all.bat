@echo off
echo ╔══════════════════════════════════════════════════════════╗
echo ║     Distributed Semi-Join — Quick Run Script            ║
echo ║     N23DCCN171 — Project #11                            ║
echo ╚══════════════════════════════════════════════════════════╝
echo.

:: ── Detect Maven ──────────────────────────────────────────────────────────
set MVN_CMD=mvn
where mvn >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [WARN] 'mvn' not in PATH. Checking IntelliJ bundled Maven...
    set "IDEA_MVN=C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2024.1\plugins\maven\lib\maven3\bin\mvn.cmd"
    if exist "%IDEA_MVN%" (
        set MVN_CMD="%IDEA_MVN%"
        echo [OK] Found IntelliJ Maven.
    ) else (
        echo [ERROR] Maven not found!
        echo Please install Maven: https://maven.apache.org/download.cgi
        echo Or open projects in IntelliJ IDEA and run from there.
        pause
        exit /b 1
    )
)

echo [INFO] Maven: %MVN_CMD%
echo.

:: ── Step 1: Build both projects ────────────────────────────────────────────
echo [STEP 1] Building site2-assignments...
cd /d "%~dp0site2-assignments"
%MVN_CMD% clean package -q -DskipTests
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Build failed for site2-assignments!
    pause & exit /b 1
)
echo [OK] site2-assignments built.

echo.
echo [STEP 2] Building site1-employees...
cd /d "%~dp0site1-employees"
%MVN_CMD% clean package -q -DskipTests
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Build failed for site1-employees!
    pause & exit /b 1
)
echo [OK] site1-employees built.

echo.
echo ──────────────────────────────────────────────────────────
echo  Starting both services in separate windows...
echo ──────────────────────────────────────────────────────────
echo.

:: ── Step 2: Start Site 2 first ─────────────────────────────────────────────
start "Site 2 - Assignments (port 8082)" cmd /k "cd /d "%~dp0site2-assignments" && %MVN_CMD% spring-boot:run"

:: Wait 15 seconds for Site 2 to start
echo [INFO] Waiting 15s for Site 2 to initialize...
timeout /t 15 /nobreak >nul

:: ── Step 3: Start Site 1 ──────────────────────────────────────────────────
start "Site 1 - Employees (port 8081)" cmd /k "cd /d "%~dp0site1-employees" && %MVN_CMD% spring-boot:run"

echo.
echo [INFO] Waiting 20s for Site 1 to initialize...
timeout /t 20 /nobreak >nul

echo.
echo ╔══════════════════════════════════════════════════════════╗
echo ║  Both services are running! Open in browser:            ║
echo ║                                                          ║
echo ║  BENCHMARK  → http://localhost:8081/site1/benchmark     ║
echo ║  Semi-Join  → http://localhost:8081/site1/semijoin      ║
echo ║  Std Join   → http://localhost:8081/site1/standard-join ║
echo ║  Site2 Info → http://localhost:8082/site2/info          ║
echo ╚══════════════════════════════════════════════════════════╝
echo.
pause
