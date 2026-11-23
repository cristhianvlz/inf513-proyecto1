#!/usr/bin/env bash
# Compila y ejecuta MainICAP en Linux (VPS). Uso: ./run.sh [segundos entre revisiones]
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f icap.properties ]; then
    echo "Falta icap.properties: copie icap.properties.ejemplo como icap.properties y complete las claves."
    exit 1
fi

CP="bin:lib/postgresql-42.7.14.jar"
echo "Compilando..."
mkdir -p bin
javac -encoding UTF-8 -d bin -cp "$CP" MainICAP.java src/comunicacion/*.java src/modelo/*.java src/negocio/*.java \
    src/persistencia/*.java src/utilidades/*.java

echo "Iniciando MainICAP..."
exec java -Duser.timezone=America/La_Paz -Dfile.encoding=UTF-8 -Djava.awt.headless=true -cp "$CP" MainICAP "$@"
