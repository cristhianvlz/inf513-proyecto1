-- =====================================================================================
-- Migración de roles a los 4 actores del CU-01: Director, Secretaria, Profesor, Estudiante
--
--   Director   <- Director + Coordinación Académica + Encargado de Procesamiento de Notas (CPD)
--   Secretaria <- Encargado de Registro + Jefe Administrativo Financiero (JAF)
--   Profesor   <- Capacitador (mismo rol, otro nombre)
--   Estudiante <- rol nuevo: se reconoce al estudiante por el correo de su expediente
--
-- No cambia el esquema: solo datos de ROL, ROL_PERMISO, ROL_USUARIO, USUARIO y PERSONAL.
-- Todo va en una transacción: si algo no coincide con lo esperado, no se aplica nada.
-- Ejecutar una sola vez, con MainICAP detenido, y reiniciarlo con la versión nueva del código.
-- =====================================================================================
BEGIN;

-- 0. Los 6 roles anteriores deben existir tal como están; si ya se migró, se detiene aquí
DO $$
BEGIN
    IF (SELECT count(*) FROM rol WHERE nombre IN ('Director', 'Coordinación Académica', 'Encargado de Registro',
            'Jefe Administrativo Financiero', 'Capacitador', 'Encargado de Procesamiento de Notas')) <> 6 THEN
        RAISE EXCEPTION 'Los roles no son los esperados (¿la migración ya se aplicó?). No se modificó nada.';
    END IF;
END $$;

-- 1. Director recibe los permisos de Coordinación Académica y del CPD
INSERT INTO rol_permiso (id_rol, id_permiso)
SELECT DISTINCT d.id_rol, rp.id_permiso
FROM rol_permiso rp
JOIN rol o ON o.id_rol = rp.id_rol
CROSS JOIN rol d
WHERE o.nombre IN ('Coordinación Académica', 'Encargado de Procesamiento de Notas')
  AND d.nombre = 'Director'
  AND NOT EXISTS (SELECT 1 FROM rol_permiso x WHERE x.id_rol = d.id_rol AND x.id_permiso = rp.id_permiso);

-- 2. Encargado de Registro (futura Secretaria) recibe los permisos del JAF
INSERT INTO rol_permiso (id_rol, id_permiso)
SELECT DISTINCT s.id_rol, rp.id_permiso
FROM rol_permiso rp
JOIN rol o ON o.id_rol = rp.id_rol
CROSS JOIN rol s
WHERE o.nombre = 'Jefe Administrativo Financiero'
  AND s.nombre = 'Encargado de Registro'
  AND NOT EXISTS (SELECT 1 FROM rol_permiso x WHERE x.id_rol = s.id_rol AND x.id_permiso = rp.id_permiso);

-- 3. Quien era JAF pasa a Secretaria (conserva la fecha de asignación original)
INSERT INTO rol_usuario (id_rol, id_usuario, fecha_asignacion)
SELECT s.id_rol, ru.id_usuario, ru.fecha_asignacion
FROM rol_usuario ru
JOIN rol o ON o.id_rol = ru.id_rol
CROSS JOIN rol s
WHERE o.nombre = 'Jefe Administrativo Financiero'
  AND s.nombre = 'Encargado de Registro'
  AND NOT EXISTS (SELECT 1 FROM rol_usuario x WHERE x.id_rol = s.id_rol AND x.id_usuario = ru.id_usuario);

-- 4. Quien era CPD y no era Director pasa a Director (hoy el único CPD ya es Director)
INSERT INTO rol_usuario (id_rol, id_usuario, fecha_asignacion)
SELECT d.id_rol, ru.id_usuario, ru.fecha_asignacion
FROM rol_usuario ru
JOIN rol o ON o.id_rol = ru.id_rol
CROSS JOIN rol d
WHERE o.nombre = 'Encargado de Procesamiento de Notas'
  AND d.nombre = 'Director'
  AND NOT EXISTS (SELECT 1 FROM rol_usuario x WHERE x.id_rol = d.id_rol AND x.id_usuario = ru.id_usuario);

-- 5. Los usuarios de Coordinación Académica NO pasan a Director (decisión del 2026-11-21):
--    1711167cvr y cccolque2022 ya son Director y conservan ese rol; juaj345ccc se da de baja en el paso 9

-- 6. Nombres y descripciones de los roles que quedan (los id no cambian: las asignaciones se conservan)
UPDATE rol SET descripcion = 'Director del ICAP: catálogo, mallas, ofertas, asignación de profesores, actas, enmiendas, certificados, reportes y usuarios'
WHERE nombre = 'Director';
UPDATE rol SET nombre = 'Secretaria',
               descripcion = 'Secretaria: estudiantes, preinscripciones, inscripciones, expediente digital, aranceles, convenios y pagos'
WHERE nombre = 'Encargado de Registro';
UPDATE rol SET nombre = 'Profesor',
               descripcion = 'Profesor (capacitador): sesiones, asistencia, actividades y calificaciones de sus propias asignaciones'
WHERE nombre = 'Capacitador';

-- 7. Se eliminan los roles fusionados (ROL_PERMISO y ROL_USUARIO se borran en cascada)
DELETE FROM rol WHERE nombre IN ('Coordinación Académica', 'Jefe Administrativo Financiero', 'Encargado de Procesamiento de Notas');

-- 8. Rol Estudiante: consulta solo sus propios datos. No se asigna en ROL_USUARIO (el estudiante no tiene USUARIO):
--    el sistema lo reconoce por el correo registrado en su expediente (ESTUDIANTE.email)
INSERT INTO rol (nombre, descripcion)
VALUES ('Estudiante', 'Estudiante: se identifica por el correo de su expediente; consulta su kardex, estado de cuenta, documentos y horarios');
INSERT INTO rol_permiso (id_rol, id_permiso)
SELECT r.id_rol, p.id_permiso
FROM rol r CROSS JOIN permiso p
WHERE r.nombre = 'Estudiante' AND p.nombre IN ('KARDEX', 'ESTCTA', 'LISDOC', 'LISHOR');

-- 9. Baja lógica de la cuenta que solo era Coordinación Académica (como DELPER: no se borra porque está en LOG).
--    Se puede reactivar con RESPER["1042594"] y asignarle un rol con ASIGROL
UPDATE usuario SET estado = 'INACTIVO' WHERE email = 'juaj345ccc@gmail.com';
UPDATE personal SET estado = 'INACTIVO'
WHERE id_personal = (SELECT id_personal FROM usuario WHERE email = 'juaj345ccc@gmail.com');

-- 10. Resultado: deben quedar 4 roles con 72, 41, 15 y 4 permisos
SELECT r.id_rol, r.nombre, count(rp.id_permiso) AS permisos
FROM rol r LEFT JOIN rol_permiso rp ON rp.id_rol = r.id_rol
GROUP BY r.id_rol, r.nombre ORDER BY r.id_rol;

SELECT u.email, u.estado, string_agg(r.nombre, ', ' ORDER BY r.id_rol) AS roles
FROM usuario u LEFT JOIN rol_usuario ru ON ru.id_usuario = u.id_usuario LEFT JOIN rol r ON r.id_rol = ru.id_rol
GROUP BY u.id_usuario, u.email, u.estado ORDER BY u.id_usuario;

COMMIT;
