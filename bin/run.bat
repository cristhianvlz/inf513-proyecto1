@echo off
chcp 65001 > nul
echo Compilando...
javac -d bin -cp ".;lib\postgresql-42.7.14.jar" MainICAP.java src\comunicacion\*.java src\modelo\*.java src\negocio\*.java src\persistencia\*.java src\utilidades\*.java
if %ERRORLEVEL% EQU 0 (
    echo Iniciando MainICAP...
    java -cp "bin;lib\postgresql-42.7.14.jar" MainICAP %*
) else (
    echo Error durante la compilacion.
)
pause

