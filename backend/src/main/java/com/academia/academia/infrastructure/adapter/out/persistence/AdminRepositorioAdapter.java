package com.academia.academia.infrastructure.adapter.out.persistence;

import com.academia.academia.domain.model.ClasePruebaAdmin;
import com.academia.academia.domain.model.CuotaVencidaAdmin;
import com.academia.academia.domain.model.CursoAdmin;
import com.academia.academia.domain.model.HorarioCurso;
import com.academia.academia.domain.model.PagoPendienteAdmin;
import com.academia.academia.domain.model.ResumenAdmin;
import com.academia.academia.domain.model.UsuarioAdmin;
import com.academia.academia.domain.port.out.AdminRepositorioPuerto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Consultas de lectura y pequeñas actualizaciones del panel. Usa SQL directo porque son
 * listados con varios JOIN y conteos; no pasan por las entidades JPA.
 * Se crea el JdbcTemplate a partir del DataSource: así participa en las transacciones del servicio.
 */
@Component
public class AdminRepositorioAdapter implements AdminRepositorioPuerto {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    /** Tipo de usuario: el rol, y para los administrativos su cargo (DIRECTOR / SECRETARIA / RECEPCIONISTA). */
    private static final String SQL_USUARIOS = """
            SELECT u.id_usuario, u.nombre_completo, u.correo, u.ci, u.telefono, u.activo, u.fecha_registro,
                   CASE WHEN r.nombre_rol = 'ADMINISTRATIVO'
                        THEN COALESCE(a.cargo, 'ADMINISTRATIVO')
                        ELSE r.nombre_rol END AS tipo
            FROM usuario u
            JOIN rol r ON r.id_rol = u.id_rol
            LEFT JOIN administrativo a ON a.id_usuario = u.id_usuario
            """;

    private static final String SQL_CLASES = """
            SELECT cp.id_clase_prueba, p.nombre_completo, p.telefono, p.correo, c.nombre_curso,
                   cp.fecha_hora, cp.estado_clase, cp.observaciones
            FROM clase_prueba cp
            JOIN prospecto p ON p.id_prospecto = cp.id_prospecto
            JOIN curso c ON c.id_curso = cp.id_curso
            """;

    private final JdbcTemplate jdbc;

    public AdminRepositorioAdapter(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    // ---------------- Resumen ----------------

    @Override
    public ResumenAdmin resumen(boolean incluirFinanzas) {
        Map<String, Integer> porRol = new HashMap<>();
        jdbc.query("SELECT r.nombre_rol AS rol, COUNT(*) AS total FROM usuario u "
                        + "JOIN rol r ON r.id_rol = u.id_rol WHERE u.activo GROUP BY r.nombre_rol",
                (rs, i) -> porRol.put(rs.getString("rol"), rs.getInt("total")));

        Integer pagosPorVerificar = null;
        Integer cuotasVencidas = null;
        BigDecimal ingresosMes = null;
        if (incluirFinanzas) {
            pagosPorVerificar = contar("SELECT COUNT(*) FROM transaccion WHERE estado_transaccion = 'PENDIENTE'");
            cuotasVencidas = contar("SELECT COUNT(*) FROM cuota WHERE estado_cuota = 'VENCIDA' "
                    + "OR (estado_cuota = 'PENDIENTE' AND fecha_vencimiento < CURRENT_DATE)");
            ingresosMes = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(COALESCE(t.monto_recibido, p.monto_total)), 0)
                    FROM transaccion t
                    JOIN pago p ON p.id_pago = t.id_pago
                    WHERE t.estado_transaccion = 'APROBADA'
                      AND date_trunc('month', COALESCE(t.fecha_confirmacion, t.fecha_hora))
                          = date_trunc('month', CURRENT_TIMESTAMP)
                    """, BigDecimal.class);
        }

        return new ResumenAdmin(
                porRol.getOrDefault("ESTUDIANTE", 0),
                porRol.getOrDefault("DOCENTE", 0),
                porRol.getOrDefault("INVITADO", 0),
                contar("SELECT COUNT(*) FROM curso WHERE activo"),
                contar("SELECT COUNT(*) FROM inscripcion WHERE estado_inscripcion = 'ACTIVA'"),
                contar("SELECT COUNT(*) FROM clase_prueba WHERE estado_clase = 'PROGRAMADA'"),
                contar("SELECT COUNT(*) FROM prospecto WHERE estado_prospecto = 'NUEVO'"),
                contar("SELECT COUNT(*) FROM prestamo_instrumento WHERE estado_prestamo = 'ATRASADO' "
                        + "OR (estado_prestamo = 'ACTIVO' AND fecha_devolucion_prevista < CURRENT_DATE)"),
                pagosPorVerificar,
                cuotasVencidas,
                ingresosMes);
    }

    private int contar(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    // ---------------- Usuarios ----------------

    @Override
    public List<UsuarioAdmin> listarUsuarios() {
        return jdbc.query(SQL_USUARIOS + " ORDER BY u.nombre_completo", (rs, i) -> aUsuario(rs));
    }

    @Override
    public Optional<UsuarioAdmin> buscarUsuario(Integer id) {
        return jdbc.query(SQL_USUARIOS + " WHERE u.id_usuario = ?", (rs, i) -> aUsuario(rs), id)
                .stream().findFirst();
    }

    private UsuarioAdmin aUsuario(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UsuarioAdmin(
                rs.getInt("id_usuario"),
                rs.getString("nombre_completo"),
                rs.getString("correo"),
                rs.getString("ci"),
                rs.getString("telefono"),
                rs.getString("tipo"),
                rs.getBoolean("activo"),
                rs.getTimestamp("fecha_registro").toLocalDateTime());
    }

    @Override
    public void actualizarActivoUsuario(Integer id, boolean activo) {
        jdbc.update("UPDATE usuario SET activo = ? WHERE id_usuario = ?", activo, id);
    }

    // ---------------- Clases de prueba ----------------

    @Override
    public List<ClasePruebaAdmin> listarClasesPrueba() {
        return jdbc.query(SQL_CLASES + " ORDER BY cp.fecha_hora DESC", (rs, i) -> aClase(rs));
    }

    @Override
    public Optional<ClasePruebaAdmin> buscarClasePrueba(Integer id) {
        return jdbc.query(SQL_CLASES + " WHERE cp.id_clase_prueba = ?", (rs, i) -> aClase(rs), id)
                .stream().findFirst();
    }

    private ClasePruebaAdmin aClase(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ClasePruebaAdmin(
                rs.getInt("id_clase_prueba"),
                rs.getString("nombre_completo"),
                rs.getString("telefono"),
                rs.getString("correo"),
                rs.getString("nombre_curso"),
                rs.getTimestamp("fecha_hora").toLocalDateTime(),
                rs.getString("estado_clase"),
                rs.getString("observaciones"));
    }

    @Override
    public void actualizarEstadoClasePrueba(Integer id, String estado) {
        jdbc.update("UPDATE clase_prueba SET estado_clase = ? WHERE id_clase_prueba = ?", estado, id);
    }

    // ---------------- Cursos ----------------

    @Override
    public List<CursoAdmin> listarCursos() {
        // Horarios activos agrupados por curso (un solo viaje a la base)
        Map<Integer, List<HorarioCurso>> horarios = new LinkedHashMap<>();
        jdbc.query("""
                SELECT h.id_curso, h.dia, h.hora_inicio, h.hora_fin, a.nombre_aula
                FROM horario h
                JOIN aula a ON a.id_aula = h.id_aula
                WHERE h.activo
                ORDER BY h.id_curso,
                         array_position(ARRAY['LUNES','MARTES','MIERCOLES','JUEVES','VIERNES','SABADO','DOMINGO'],
                                        h.dia::text),
                         h.hora_inicio
                """,
                (rs, i) -> horarios.computeIfAbsent(rs.getInt("id_curso"), k -> new ArrayList<>())
                        .add(new HorarioCurso(
                                rs.getString("dia"),
                                rs.getTime("hora_inicio").toLocalTime().format(HORA),
                                rs.getTime("hora_fin").toLocalTime().format(HORA),
                                rs.getString("nombre_aula"))));

        return jdbc.query("""
                SELECT c.id_curso, c.nombre_curso, u.nombre_completo AS docente, c.nivel, c.costo_mensual, c.activo,
                       (SELECT COUNT(*) FROM inscripcion i
                         WHERE i.id_curso = c.id_curso AND i.estado_inscripcion = 'ACTIVA') AS inscritos
                FROM curso c
                JOIN usuario u ON u.id_usuario = c.id_usuario
                ORDER BY c.activo DESC, c.nombre_curso
                """,
                (rs, i) -> new CursoAdmin(
                        rs.getInt("id_curso"),
                        rs.getString("nombre_curso"),
                        rs.getString("docente"),
                        rs.getString("nivel"),
                        rs.getBigDecimal("costo_mensual"),
                        rs.getBoolean("activo"),
                        rs.getInt("inscritos"),
                        horarios.getOrDefault(rs.getInt("id_curso"), List.of())));
    }

    // ---------------- Pagos ----------------

    @Override
    public List<PagoPendienteAdmin> listarPagosPendientes() {
        return jdbc.query("""
                SELECT t.id_transaccion, u.nombre_completo, cu.nombre_curso, p.concepto, p.monto_total,
                       t.metodo_pago, t.nro_comprobante, t.fecha_hora
                FROM transaccion t
                JOIN pago p ON p.id_pago = t.id_pago
                JOIN usuario u ON u.id_usuario = p.id_usuario
                JOIN inscripcion i ON i.id_inscripcion = p.id_inscripcion
                JOIN curso cu ON cu.id_curso = i.id_curso
                WHERE t.estado_transaccion = 'PENDIENTE'
                ORDER BY t.fecha_hora
                """,
                (rs, i) -> new PagoPendienteAdmin(
                        rs.getInt("id_transaccion"),
                        rs.getString("nombre_completo"),
                        rs.getString("nombre_curso"),
                        rs.getString("concepto"),
                        rs.getBigDecimal("monto_total"),
                        rs.getString("metodo_pago"),
                        rs.getString("nro_comprobante"),
                        rs.getTimestamp("fecha_hora").toLocalDateTime()));
    }

    @Override
    public List<CuotaVencidaAdmin> listarCuotasVencidas() {
        return jdbc.query("""
                SELECT q.id_cuota, u.nombre_completo, u.telefono, cu.nombre_curso,
                       q.mes, q.anio, q.monto, q.fecha_vencimiento
                FROM cuota q
                JOIN inscripcion i ON i.id_inscripcion = q.id_inscripcion
                JOIN usuario u ON u.id_usuario = i.id_usuario
                JOIN curso cu ON cu.id_curso = i.id_curso
                WHERE q.estado_cuota = 'VENCIDA'
                   OR (q.estado_cuota = 'PENDIENTE' AND q.fecha_vencimiento < CURRENT_DATE)
                ORDER BY q.fecha_vencimiento, u.nombre_completo
                """,
                (rs, i) -> new CuotaVencidaAdmin(
                        rs.getInt("id_cuota"),
                        rs.getString("nombre_completo"),
                        rs.getString("telefono"),
                        rs.getString("nombre_curso"),
                        rs.getInt("mes"),
                        rs.getInt("anio"),
                        rs.getBigDecimal("monto"),
                        rs.getDate("fecha_vencimiento").toLocalDate()));
    }

    // ---------------- Bitácora ----------------

    @Override
    public void registrarBitacora(Integer idUsuario, String accion, String tabla, String idRegistro,
                                  String descripcion, String ip) {
        jdbc.update("INSERT INTO bitacora (id_usuario, accion, tabla_afectada, id_registro, descripcion, ip_origen) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                idUsuario, accion, tabla, idRegistro, descripcion, ip);
    }
}
