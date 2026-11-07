package com.academia.academia.infrastructure.adapter.out.persistence;

import com.academia.academia.domain.model.EstudiantePanel.AsistenciaEstudiante;
import com.academia.academia.domain.model.EstudiantePanel.CuotaEstudiante;
import com.academia.academia.domain.model.EstudiantePanel.CursoEstudiante;
import com.academia.academia.domain.model.EstudiantePanel.EvaluacionEstudiante;
import com.academia.academia.domain.model.EstudiantePanel.EventoEstudiante;
import com.academia.academia.domain.model.EstudiantePanel.HorarioItem;
import com.academia.academia.domain.model.EstudiantePanel.MaterialEstudiante;
import com.academia.academia.domain.model.EstudiantePanel.PrestamoEstudiante;
import com.academia.academia.domain.model.EstudiantePanel.ProximaClase;
import com.academia.academia.domain.model.EstudiantePanel.Resumen;
import com.academia.academia.domain.port.out.EstudianteRepositorioPuerto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Consultas de lectura del panel del estudiante (SQL directo, igual que el panel de administración).
 * Todas filtran por el id del estudiante que viene de la sesión.
 */
@Component
public class EstudianteRepositorioAdapter implements EstudianteRepositorioPuerto {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    private static final String ORDEN_DIA = """
            array_position(ARRAY['LUNES','MARTES','MIERCOLES','JUEVES','VIERNES','SABADO','DOMINGO'], h.dia::text)""";

    /** Una cuota PENDIENTE cuya fecha ya pasó se muestra como VENCIDA (la tarea programada puede tardar en marcarla). */
    private static final String ESTADO_CUOTA = """
            CASE WHEN q.estado_cuota = 'PENDIENTE' AND q.fecha_vencimiento < CURRENT_DATE
                 THEN 'VENCIDA' ELSE q.estado_cuota END""";

    private final JdbcTemplate jdbc;

    public EstudianteRepositorioAdapter(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<Integer> buscarIdPorCorreo(String correo) {
        return jdbc.query("""
                        SELECT e.id_usuario
                        FROM estudiante e
                        JOIN usuario u ON u.id_usuario = e.id_usuario
                        WHERE lower(u.correo) = lower(?) AND u.activo
                        """,
                (rs, i) -> rs.getInt(1), correo).stream().findFirst();
    }

    // ---------------- Resumen ----------------

    @Override
    public Resumen resumen(Integer id) {
        int cursosActivos = contar("SELECT COUNT(*) FROM inscripcion WHERE id_usuario = ? AND estado_inscripcion = 'ACTIVA'", id);

        int pendientes = contar("""
                SELECT COUNT(*) FROM cuota q JOIN inscripcion i ON i.id_inscripcion = q.id_inscripcion
                WHERE i.id_usuario = ? AND q.estado_cuota = 'PENDIENTE' AND q.fecha_vencimiento >= CURRENT_DATE
                """, id);
        int vencidas = contar("""
                SELECT COUNT(*) FROM cuota q JOIN inscripcion i ON i.id_inscripcion = q.id_inscripcion
                WHERE i.id_usuario = ?
                  AND (q.estado_cuota = 'VENCIDA' OR (q.estado_cuota = 'PENDIENTE' AND q.fecha_vencimiento < CURRENT_DATE))
                """, id);
        BigDecimal montoPorPagar = jdbc.queryForObject("""
                SELECT COALESCE(SUM(q.monto), 0) FROM cuota q JOIN inscripcion i ON i.id_inscripcion = q.id_inscripcion
                WHERE i.id_usuario = ? AND q.estado_cuota IN ('PENDIENTE', 'VENCIDA')
                """, BigDecimal.class, id);

        int enRevision = contar("""
                SELECT COUNT(*) FROM transaccion t JOIN pago p ON p.id_pago = t.id_pago
                WHERE p.id_usuario = ? AND t.estado_transaccion = 'PENDIENTE'
                """, id);

        // Asistencia: presente y tardanza cuentan como asistidas; la licencia no pesa en el porcentaje
        Integer asistencia = jdbc.query("""
                SELECT COUNT(*) FILTER (WHERE a.estado_asistencia IN ('PRESENTE', 'TARDANZA')) AS asistidas,
                       COUNT(*) FILTER (WHERE a.estado_asistencia <> 'LICENCIA') AS validas
                FROM asistencia a JOIN inscripcion i ON i.id_inscripcion = a.id_inscripcion
                WHERE i.id_usuario = ?
                """, rs -> {
            int validas = rs.next() ? rs.getInt("validas") : 0;
            return validas == 0 ? null : (int) Math.round(rs.getInt("asistidas") * 100.0 / validas);
        }, id);

        BigDecimal promedio = jdbc.queryForObject("""
                SELECT AVG(e.nota) FROM evaluacion e JOIN inscripcion i ON i.id_inscripcion = e.id_inscripcion
                WHERE i.id_usuario = ? AND e.nota IS NOT NULL
                """, BigDecimal.class, id);
        if (promedio != null) promedio = promedio.setScale(1, RoundingMode.HALF_UP);

        int prestamos = contar("""
                SELECT COUNT(*) FROM prestamo_instrumento
                WHERE id_usuario_prestatario = ? AND estado_prestamo IN ('ACTIVO', 'ATRASADO')
                """, id);

        return new Resumen(cursosActivos, pendientes, vencidas, montoPorPagar, enRevision,
                asistencia, promedio, prestamos, proximaClase(id));
    }

    private int contar(String sql, Integer id) {
        Integer n = jdbc.queryForObject(sql, Integer.class, id);
        return n == null ? 0 : n;
    }

    /** De los horarios de sus cursos activos, el que empieza antes a partir de este momento (la semana se repite). */
    private ProximaClase proximaClase(Integer id) {
        record Candidata(ProximaClase clase, LocalDateTime cuando) {}

        LocalDateTime ahora = LocalDateTime.now();
        List<Candidata> candidatas = jdbc.query("""
                SELECT c.nombre_curso, h.dia, h.hora_inicio, h.hora_fin, a.nombre_aula
                FROM inscripcion i
                JOIN curso c ON c.id_curso = i.id_curso AND c.activo
                JOIN horario h ON h.id_curso = c.id_curso AND h.activo
                JOIN aula a ON a.id_aula = h.id_aula
                WHERE i.id_usuario = ? AND i.estado_inscripcion = 'ACTIVA'
                """, (rs, n) -> {
            DayOfWeek dia = aDiaSemana(rs.getString("dia"));
            LocalTime inicio = rs.getTime("hora_inicio").toLocalTime();
            LocalDateTime cuando = ahora.toLocalDate().atTime(inicio)
                    .plusDays(Math.floorMod(dia.getValue() - ahora.getDayOfWeek().getValue(), 7));
            if (cuando.isBefore(ahora)) cuando = cuando.plusWeeks(1);
            return new Candidata(new ProximaClase(
                    rs.getString("nombre_curso"),
                    rs.getString("dia"),
                    inicio.format(HORA),
                    rs.getTime("hora_fin").toLocalTime().format(HORA),
                    rs.getString("nombre_aula")), cuando);
        }, id);

        return candidatas.stream().min(Comparator.comparing(Candidata::cuando)).map(Candidata::clase).orElse(null);
    }

    private static DayOfWeek aDiaSemana(String dia) {
        return switch (dia.toUpperCase(Locale.ROOT)) {
            case "LUNES" -> DayOfWeek.MONDAY;
            case "MARTES" -> DayOfWeek.TUESDAY;
            case "MIERCOLES" -> DayOfWeek.WEDNESDAY;
            case "JUEVES" -> DayOfWeek.THURSDAY;
            case "VIERNES" -> DayOfWeek.FRIDAY;
            case "SABADO" -> DayOfWeek.SATURDAY;
            default -> DayOfWeek.SUNDAY;
        };
    }

    // ---------------- Cursos ----------------

    @Override
    public List<CursoEstudiante> cursos(Integer id) {
        Map<Integer, List<HorarioItem>> horarios = new LinkedHashMap<>();
        jdbc.query("SELECT h.id_curso, h.dia, h.hora_inicio, h.hora_fin, a.nombre_aula "
                        + "FROM horario h JOIN aula a ON a.id_aula = h.id_aula "
                        + "WHERE h.activo AND h.id_curso IN (SELECT id_curso FROM inscripcion WHERE id_usuario = ?) "
                        + "ORDER BY h.id_curso, " + ORDEN_DIA + ", h.hora_inicio",
                (rs, n) -> horarios.computeIfAbsent(rs.getInt("id_curso"), k -> new ArrayList<>())
                        .add(new HorarioItem(
                                rs.getString("dia"),
                                rs.getTime("hora_inicio").toLocalTime().format(HORA),
                                rs.getTime("hora_fin").toLocalTime().format(HORA),
                                rs.getString("nombre_aula"))),
                id);

        return jdbc.query("""
                SELECT i.id_inscripcion, c.id_curso, c.nombre_curso, c.nivel, c.costo_mensual,
                       u.nombre_completo AS docente, i.estado_inscripcion, i.fecha_inscripcion
                FROM inscripcion i
                JOIN curso c ON c.id_curso = i.id_curso
                JOIN usuario u ON u.id_usuario = c.id_usuario
                WHERE i.id_usuario = ?
                ORDER BY (i.estado_inscripcion = 'ACTIVA') DESC, i.fecha_inscripcion DESC
                """,
                (rs, n) -> new CursoEstudiante(
                        rs.getInt("id_inscripcion"),
                        rs.getInt("id_curso"),
                        rs.getString("nombre_curso"),
                        rs.getString("nivel"),
                        rs.getString("docente"),
                        rs.getString("estado_inscripcion"),
                        rs.getTimestamp("fecha_inscripcion").toLocalDateTime(),
                        rs.getBigDecimal("costo_mensual"),
                        horarios.getOrDefault(rs.getInt("id_curso"), List.of())),
                id);
    }

    // ---------------- Cuotas ----------------

    @Override
    public List<CuotaEstudiante> cuotas(Integer id) {
        return jdbc.query("SELECT q.id_cuota, c.nombre_curso, q.mes, q.anio, q.monto, q.fecha_vencimiento, "
                        + ESTADO_CUOTA + " AS estado, "
                        + """
                          EXISTS (SELECT 1 FROM pago p JOIN transaccion t ON t.id_pago = p.id_pago
                                   WHERE p.id_cuota = q.id_cuota AND t.estado_transaccion = 'PENDIENTE') AS en_revision
                          FROM cuota q
                          JOIN inscripcion i ON i.id_inscripcion = q.id_inscripcion
                          JOIN curso c ON c.id_curso = i.id_curso
                          WHERE i.id_usuario = ?
                          ORDER BY q.anio DESC, q.mes DESC, c.nombre_curso
                          """,
                (rs, n) -> new CuotaEstudiante(
                        rs.getInt("id_cuota"),
                        rs.getString("nombre_curso"),
                        rs.getInt("mes"),
                        rs.getInt("anio"),
                        rs.getBigDecimal("monto"),
                        rs.getDate("fecha_vencimiento").toLocalDate(),
                        rs.getString("estado"),
                        rs.getBoolean("en_revision")),
                id);
    }

    // ---------------- Asistencia y notas ----------------

    @Override
    public List<AsistenciaEstudiante> asistencia(Integer id) {
        return jdbc.query("""
                SELECT c.nombre_curso, a.fecha, a.estado_asistencia, a.observacion
                FROM asistencia a
                JOIN inscripcion i ON i.id_inscripcion = a.id_inscripcion
                JOIN curso c ON c.id_curso = i.id_curso
                WHERE i.id_usuario = ?
                ORDER BY a.fecha DESC, c.nombre_curso
                """,
                (rs, n) -> new AsistenciaEstudiante(
                        rs.getString("nombre_curso"),
                        rs.getDate("fecha").toLocalDate(),
                        rs.getString("estado_asistencia"),
                        rs.getString("observacion")),
                id);
    }

    @Override
    public List<EvaluacionEstudiante> evaluaciones(Integer id) {
        return jdbc.query("""
                SELECT c.nombre_curso, e.nombre_evaluacion, e.nota, e.fecha_evaluacion, e.comentario
                FROM evaluacion e
                JOIN inscripcion i ON i.id_inscripcion = e.id_inscripcion
                JOIN curso c ON c.id_curso = i.id_curso
                WHERE i.id_usuario = ?
                ORDER BY e.fecha_evaluacion DESC NULLS LAST, c.nombre_curso
                """,
                (rs, n) -> new EvaluacionEstudiante(
                        rs.getString("nombre_curso"),
                        rs.getString("nombre_evaluacion"),
                        rs.getBigDecimal("nota"),
                        rs.getDate("fecha_evaluacion") == null ? null : rs.getDate("fecha_evaluacion").toLocalDate(),
                        rs.getString("comentario")),
                id);
    }

    // ---------------- Material de apoyo ----------------

    @Override
    public List<MaterialEstudiante> material(Integer id) {
        // Solo el material de los cursos donde tiene una inscripción ACTIVA
        return jdbc.query("""
                SELECT m.id_material, c.nombre_curso, m.titulo, m.descripcion, m.tipo_material,
                       m.url_archivo, d.nombre_completo AS docente, m.fecha_subida
                FROM material_apoyo m
                JOIN curso c ON c.id_curso = m.id_curso
                JOIN usuario d ON d.id_usuario = m.id_usuario
                WHERE m.activo
                  AND EXISTS (SELECT 1 FROM inscripcion i
                               WHERE i.id_curso = m.id_curso AND i.id_usuario = ? AND i.estado_inscripcion = 'ACTIVA')
                ORDER BY m.fecha_subida DESC
                """,
                (rs, n) -> new MaterialEstudiante(
                        rs.getInt("id_material"),
                        rs.getString("nombre_curso"),
                        rs.getString("titulo"),
                        rs.getString("descripcion"),
                        rs.getString("tipo_material"),
                        rs.getString("url_archivo"),
                        rs.getString("docente"),
                        rs.getTimestamp("fecha_subida").toLocalDateTime()),
                id);
    }

    // ---------------- Préstamos ----------------

    @Override
    public List<PrestamoEstudiante> prestamos(Integer id) {
        return jdbc.query("""
                SELECT p.id_prestamo, ins.nombre_instrumento, ins.codigo_inventario, p.fecha_prestamo,
                       p.fecha_devolucion_prevista, p.fecha_devolucion_real,
                       CASE WHEN p.estado_prestamo = 'ACTIVO' AND p.fecha_devolucion_prevista < CURRENT_DATE
                            THEN 'ATRASADO' ELSE p.estado_prestamo END AS estado
                FROM prestamo_instrumento p
                JOIN instrumento ins ON ins.id_instrumento = p.id_instrumento
                WHERE p.id_usuario_prestatario = ?
                ORDER BY (p.estado_prestamo IN ('ACTIVO', 'ATRASADO')) DESC, p.fecha_prestamo DESC
                """,
                (rs, n) -> new PrestamoEstudiante(
                        rs.getInt("id_prestamo"),
                        rs.getString("nombre_instrumento"),
                        rs.getString("codigo_inventario"),
                        rs.getTimestamp("fecha_prestamo").toLocalDateTime(),
                        rs.getDate("fecha_devolucion_prevista").toLocalDate(),
                        rs.getTimestamp("fecha_devolucion_real") == null
                                ? null : rs.getTimestamp("fecha_devolucion_real").toLocalDateTime(),
                        rs.getString("estado")),
                id);
    }

    // ---------------- Eventos ----------------

    @Override
    public List<EventoEstudiante> eventos(Integer id) {
        return jdbc.query("""
                SELECT e.id_evento, e.nombre_evento, e.descripcion, e.tipo_evento, e.fecha_hora_inicio,
                       e.fecha_hora_fin, COALESCE(e.lugar_externo, a.nombre_aula) AS lugar, e.estado_evento,
                       pe.estado_participacion
                FROM evento e
                LEFT JOIN aula a ON a.id_aula = e.id_aula
                LEFT JOIN participacion_evento pe ON pe.id_evento = e.id_evento AND pe.id_usuario = ?
                WHERE e.estado_evento IN ('APROBADO', 'REALIZADO')
                ORDER BY e.fecha_hora_inicio DESC
                """,
                (rs, n) -> aEvento(rs),
                id);
    }

    private EventoEstudiante aEvento(ResultSet rs) throws SQLException {
        return new EventoEstudiante(
                rs.getInt("id_evento"),
                rs.getString("nombre_evento"),
                rs.getString("descripcion"),
                rs.getString("tipo_evento"),
                rs.getTimestamp("fecha_hora_inicio").toLocalDateTime(),
                rs.getTimestamp("fecha_hora_fin") == null ? null : rs.getTimestamp("fecha_hora_fin").toLocalDateTime(),
                rs.getString("lugar"),
                rs.getString("estado_evento"),
                rs.getString("estado_participacion"));
    }
}
