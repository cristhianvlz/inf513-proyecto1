@echo off
chcp 65001 > nul
if not exist icap.properties (
    echo Falta icap.properties: copie icap.properties.ejemplo como icap.properties y complete las claves.
    pause
    exit /b 1
)
echo Compilando...
javac -encoding UTF-8 -d bin -cp ".;lib\postgresql-42.7.14.jar" MainICAP.java src\comunicacion\*.java src\modelo\*.java src\negocio\*.java src\persistencia\*.java src\utilidades\*.java
if %ERRORLEVEL% EQU 0 (
    echo Iniciando MainICAP...
    java -Duser.timezone=America/La_Paz -Dfile.encoding=UTF-8 -cp "bin;lib\postgresql-42.7.14.jar" MainICAP %*
) else (
    echo Error durante la compilacion.
)
pause
