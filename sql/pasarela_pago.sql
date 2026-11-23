-- =====================================================================================
-- Permiso del comando GENPAGO: CU-04 Flujo E, pago en línea por la pasarela.
-- Lo pueden enviar la Secretaria (para cualquier estudiante) y el Estudiante (solo para sí mismo).
--
-- Solo agrega datos (PERMISO y ROL_PERMISO): no cambia el esquema. La pasarela usa la tabla PAGO tal como está:
--   numero_transaccion = código de la orden, relacion_depositante = 'PASARELA',
--   estado_conciliacion: PENDIENTE (sin pagar) -> CONCILIADO (pagada) | OBSERVADO (otro monto) | ANULADO (vencida).
-- Se puede ejecutar más de una vez: si el permiso ya existe, no hace nada.
-- =====================================================================================
BEGIN;

INSERT INTO permiso (nombre, descripcion)
SELECT 'GENPAGO', 'Generar una orden de pago en la pasarela (código QR)'
WHERE NOT EXISTS (SELECT 1 FROM permiso WHERE nombre = 'GENPAGO');

INSERT INTO rol_permiso (id_rol, id_permiso)
SELECT r.id_rol, p.id_permiso
FROM rol r CROSS JOIN permiso p
WHERE r.nombre IN ('Secretaria', 'Estudiante') AND p.nombre = 'GENPAGO'
  AND NOT EXISTS (SELECT 1 FROM rol_permiso x WHERE x.id_rol = r.id_rol AND x.id_permiso = p.id_permiso);

-- Resultado esperado: Director 72, Secretaria 42, Profesor 15, Estudiante 5
SELECT r.nombre, count(rp.id_permiso) AS permisos
FROM rol r LEFT JOIN rol_permiso rp ON rp.id_rol = r.id_rol
GROUP BY r.id_rol, r.nombre ORDER BY r.id_rol;

COMMIT;
