-- Sistema ICAP (Grupo 15 SC) · Vaciar la base para empezar de cero
--
-- Borra TODOS los registros de las tablas, menos lo que el software necesita para funcionar:
--   ROL, PERMISO, ROL_PERMISO        (los 4 actores y sus permisos)
--   TIPO_EVALUACION                  (ASISTENCIA, TRABAJOS PRACTICOS, PARCIAL, FINAL, RECUPERATORIO: ningún comando los carga)
--   las cuentas de la lista de abajo, con su PERSONAL y sus roles (ROL_USUARIO)
--
-- ANTES: genere un respaldo con  java -cp lib/postgresql-42.7.14.jar deploy/RespaldoBD.java
-- Se ejecuta COMPLETO, de una sola vez. Si algo falla, no se borra nada (es una sola transacción).
-- No hace falta tocar correos_procesados.txt: los correos ya ejecutados siguen marcados y no se repiten.

BEGIN;

-- 1) Cuentas que se conservan (agregue una línea por correo, por ejemplo la de otro Director del grupo)
CREATE TEMP TABLE conservar_cuenta (email varchar(150) PRIMARY KEY) ON COMMIT DROP;
INSERT INTO conservar_cuenta (email) VALUES
    ('1711167cvr@gmail.com');

-- 2) Seguridad: si algún correo de la lista no existe, se cancela todo
DO $$
DECLARE
    faltan text;
BEGIN
    SELECT string_agg(c.email, ', ') INTO faltan
    FROM conservar_cuenta c
    WHERE NOT EXISTS (SELECT 1 FROM usuario u WHERE lower(u.email) = lower(c.email));
    IF faltan IS NOT NULL THEN
        RAISE EXCEPTION 'No existe la cuenta: %. No se borró nada.', faltan;
    END IF;
END $$;

-- 3) Datos académicos, financieros, documentales y la bitácora (los contadores de id vuelven a 1)
TRUNCATE TABLE
    asistencia, calificacion, enmienda_nota, historial_estado, documento_digital, pago, certificado,
    inscripcion_modulo, inscripcion, convenio, arancel,
    actividad_evaluativa, acta_calificacion, sesion_clase, horario, asignacion_modulo, capacitor,
    oferta_academica, modulo_version_prerrequisito, modulo_version, version_programa,
    programa_prerrequisito, programa, modulo, estudiante, log
    RESTART IDENTITY;

-- 4) Las demás cuentas y su personal
DELETE FROM rol_usuario
WHERE id_usuario NOT IN (SELECT u.id_usuario FROM usuario u JOIN conservar_cuenta c ON lower(c.email) = lower(u.email));

DELETE FROM usuario
WHERE lower(email) NOT IN (SELECT lower(email) FROM conservar_cuenta);

DELETE FROM personal
WHERE id_personal NOT IN (SELECT id_personal FROM usuario);

-- 5) Las cuentas conservadas quedan activas
UPDATE usuario SET estado = 'ACTIVO' WHERE estado <> 'ACTIVO';
UPDATE personal SET estado = 'ACTIVO' WHERE estado <> 'ACTIVO';

COMMIT;

-- 6) Verificación: debe mostrar sus cuentas con sus roles, 4 roles, 119 permisos y 5 tipos de evaluación
SELECT u.usuario, u.email, p.ci, p.nombre || ' ' || p.apellidos AS nombre,
       (SELECT string_agg(r.nombre, ', ' ORDER BY r.nombre) FROM rol_usuario ru JOIN rol r ON r.id_rol = ru.id_rol
        WHERE ru.id_usuario = u.id_usuario) AS roles,
       (SELECT count(*) FROM rol) AS roles_total,
       (SELECT count(*) FROM permiso) AS permisos,
       (SELECT count(*) FROM rol_permiso) AS permisos_por_rol,
       (SELECT count(*) FROM tipo_evaluacion) AS tipos_evaluacion,
       (SELECT count(*) FROM estudiante) AS estudiantes
FROM usuario u JOIN personal p ON p.id_personal = u.id_personal;
