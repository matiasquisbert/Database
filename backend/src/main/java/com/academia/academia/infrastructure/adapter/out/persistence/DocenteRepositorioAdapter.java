package com.academia.academia.infrastructure.adapter.out.persistence;

import com.academia.academia.domain.model.DocentePanel;
import com.academia.academia.domain.model.DocentePanel.AlumnoCurso;
import com.academia.academia.domain.model.DocentePanel.AsistenciaAlumno;
import com.academia.academia.domain.model.DocentePanel.CursoDocente;
import com.academia.academia.domain.model.DocentePanel.EvaluacionDocente;
import com.academia.academia.domain.model.DocentePanel.HorarioItem;
import com.academia.academia.domain.model.DocentePanel.MaterialDocente;
import com.academia.academia.domain.model.DocentePanel.ProximaClase;
import com.academia.academia.domain.port.out.DocenteRepositorioPuerto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Consultas y escrituras del panel del docente (SQL directo, igual que los paneles de administración y estudiante).
 * Las operaciones que reciben un id de curso, evaluación o material se llaman solo después de que el servicio
 * comprobó que son del docente de la sesión.
 */
@Component
public class DocenteRepositorioAdapter implements DocenteRepositorioPuerto {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<String> DIAS = List.of("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO");

    private static final String ORDEN_DIA = """
            array_position(ARRAY['LUNES','MARTES','MIERCOLES','JUEVES','VIERNES','SABADO','DOMINGO'], h.dia::text)""";

    private static final String SQL_EVALUACION = """
            SELECT e.id_evaluacion, e.id_inscripcion, u.nombre_completo, e.nombre_evaluacion,
                   e.nota, e.fecha_evaluacion, e.comentario
            FROM evaluacion e
            JOIN inscripcion i ON i.id_inscripcion = e.id_inscripcion
            JOIN usuario u ON u.id_usuario = i.id_usuario
            JOIN curso c ON c.id_curso = i.id_curso
            """;

    private static final String SQL_MATERIAL = """
            SELECT m.id_material, m.id_curso, c.nombre_curso, m.titulo, m.descripcion, m.tipo_material,
                   m.url_archivo, m.fecha_subida, m.activo
            FROM material_apoyo m
            JOIN curso c ON c.id_curso = m.id_curso
            """;

    private record FilaHorario(String curso, String dia, LocalTime inicio, LocalTime fin, String aula) {}

    private final JdbcTemplate jdbc;

    public DocenteRepositorioAdapter(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<Integer> buscarIdPorCorreo(String correo) {
        return jdbc.query("""
                        SELECT d.id_usuario
                        FROM docente d
                        JOIN usuario u ON u.id_usuario = d.id_usuario
                        WHERE lower(u.correo) = lower(?) AND u.activo
                        """,
                (rs, i) -> rs.getInt(1), correo).stream().findFirst();
    }

    // ---------------- Resumen ----------------

    @Override
    public DocentePanel.Resumen resumen(Integer id) {
        int cursosActivos = contar("SELECT COUNT(*) FROM curso WHERE id_usuario = ? AND activo", id);

        int alumnos = contar("""
                SELECT COUNT(DISTINCT i.id_usuario)
                FROM inscripcion i JOIN curso c ON c.id_curso = i.id_curso
                WHERE c.id_usuario = ? AND c.activo AND i.estado_inscripcion = 'ACTIVA'
                """, id);

        String hoy = DIAS.get(LocalDate.now().getDayOfWeek().getValue() - 1);
        Integer clasesHoy = jdbc.queryForObject("""
                SELECT COUNT(*) FROM horario h JOIN curso c ON c.id_curso = h.id_curso
                WHERE c.id_usuario = ? AND c.activo AND h.activo AND h.dia = ?
                """, Integer.class, id, hoy);

        int sinCalificar = contar("""
                SELECT COUNT(*)
                FROM evaluacion e
                JOIN inscripcion i ON i.id_inscripcion = e.id_inscripcion
                JOIN curso c ON c.id_curso = i.id_curso
                WHERE c.id_usuario = ? AND c.activo AND i.estado_inscripcion = 'ACTIVA' AND e.nota IS NULL
                """, id);

        // Presente y tardanza cuentan como asistidas; la licencia no pesa en el porcentaje
        Integer asistencia = jdbc.query("""
                SELECT COUNT(*) FILTER (WHERE a.estado_asistencia IN ('PRESENTE', 'TARDANZA')) AS asistidas,
                       COUNT(*) FILTER (WHERE a.estado_asistencia <> 'LICENCIA') AS validas
                FROM asistencia a
                JOIN inscripcion i ON i.id_inscripcion = a.id_inscripcion
                JOIN curso c ON c.id_curso = i.id_curso
                WHERE c.id_usuario = ?
                """, rs -> {
            int validas = rs.next() ? rs.getInt("validas") : 0;
            return validas == 0 ? null : (int) Math.round(rs.getInt("asistidas") * 100.0 / validas);
        }, id);

        return new DocentePanel.Resumen(cursosActivos, alumnos, clasesHoy == null ? 0 : clasesHoy,
                sinCalificar, asistencia, proximaClase(id));
    }

    private int contar(String sql, Integer id) {
        Integer n = jdbc.queryForObject(sql, Integer.class, id);
        return n == null ? 0 : n;
    }

    /** La clase más cercana de aquí en adelante, mirando los horarios activos de sus cursos activos. */
    private ProximaClase proximaClase(Integer idDocente) {
        List<FilaHorario> filas = jdbc.query("""
                SELECT c.nombre_curso, h.dia, h.hora_inicio, h.hora_fin, a.nombre_aula
                FROM horario h
                JOIN curso c ON c.id_curso = h.id_curso
                JOIN aula a ON a.id_aula = h.id_aula
                WHERE c.id_usuario = ? AND c.activo AND h.activo
                """,
                (rs, i) -> new FilaHorario(rs.getString("nombre_curso"), rs.getString("dia"),
                        rs.getTime("hora_inicio").toLocalTime(), rs.getTime("hora_fin").toLocalTime(),
                        rs.getString("nombre_aula")),
                idDocente);

        LocalDateTime ahora = LocalDateTime.now();
        ProximaClase mejor = null;
        LocalDateTime mejorMomento = null;
        for (FilaHorario f : filas) {
            int idx = DIAS.indexOf(f.dia());
            if (idx < 0) continue;
            LocalDate dia = ahora.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.of(idx + 1)));
            LocalDateTime momento = dia.atTime(f.inicio());
            if (momento.isBefore(ahora)) {
                dia = dia.plusWeeks(1);
                momento = dia.atTime(f.inicio());
            }
            if (mejorMomento == null || momento.isBefore(mejorMomento)) {
                mejorMomento = momento;
                mejor = new ProximaClase(f.curso(), f.dia(), dia, f.inicio().format(HORA), f.fin().format(HORA), f.aula());
            }
        }
        return mejor;
    }

    // ---------------- Cursos y alumnos ----------------

    @Override
    public List<CursoDocente> cursos(Integer idDocente) {
        Map<Integer, List<HorarioItem>> horarios = new LinkedHashMap<>();
        jdbc.query("""
                SELECT h.id_curso, h.dia, h.hora_inicio, h.hora_fin, a.nombre_aula
                FROM horario h
                JOIN curso c ON c.id_curso = h.id_curso
                JOIN aula a ON a.id_aula = h.id_aula
                WHERE c.id_usuario = ? AND h.activo
                ORDER BY h.id_curso, """ + ORDEN_DIA + ", h.hora_inicio",
                (rs, i) -> horarios.computeIfAbsent(rs.getInt("id_curso"), k -> new ArrayList<>())
                        .add(new HorarioItem(rs.getString("dia"),
                                rs.getTime("hora_inicio").toLocalTime().format(HORA),
                                rs.getTime("hora_fin").toLocalTime().format(HORA),
                                rs.getString("nombre_aula"))),
                idDocente);

        return jdbc.query("""
                SELECT c.id_curso, c.nombre_curso, c.nivel, c.activo,
                       (SELECT COUNT(*) FROM inscripcion i
                         WHERE i.id_curso = c.id_curso AND i.estado_inscripcion = 'ACTIVA') AS alumnos
                FROM curso c
                WHERE c.id_usuario = ?
                ORDER BY c.activo DESC, c.nombre_curso
                """,
                (rs, i) -> new CursoDocente(rs.getInt("id_curso"), rs.getString("nombre_curso"), rs.getString("nivel"),
                        rs.getBoolean("activo"), rs.getInt("alumnos"),
                        horarios.getOrDefault(rs.getInt("id_curso"), List.of())),
                idDocente);
    }

    @Override
    public boolean dictaCurso(Integer idDocente, Integer idCurso) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM curso WHERE id_curso = ? AND id_usuario = ?",
                Integer.class, idCurso, idDocente);
        return n != null && n > 0;
    }

    @Override
    public List<AlumnoCurso> alumnos(Integer idCurso) {
        return jdbc.query("""
                SELECT i.id_inscripcion, u.nombre_completo, u.correo,
                       (SELECT ROUND(100.0 * COUNT(*) FILTER (WHERE a.estado_asistencia IN ('PRESENTE', 'TARDANZA'))
                                     / NULLIF(COUNT(*) FILTER (WHERE a.estado_asistencia <> 'LICENCIA'), 0))
                          FROM asistencia a WHERE a.id_inscripcion = i.id_inscripcion) AS asistencia,
                       (SELECT AVG(e.nota) FROM evaluacion e
                         WHERE e.id_inscripcion = i.id_inscripcion AND e.nota IS NOT NULL) AS promedio
                FROM inscripcion i
                JOIN usuario u ON u.id_usuario = i.id_usuario
                WHERE i.id_curso = ? AND i.estado_inscripcion = 'ACTIVA'
                ORDER BY u.nombre_completo
                """,
                (rs, i) -> {
                    BigDecimal asistencia = rs.getBigDecimal("asistencia");
                    BigDecimal promedio = rs.getBigDecimal("promedio");
                    return new AlumnoCurso(rs.getInt("id_inscripcion"), rs.getString("nombre_completo"),
                            rs.getString("correo"),
                            asistencia == null ? null : asistencia.intValue(),
                            promedio == null ? null : promedio.setScale(1, RoundingMode.HALF_UP));
                },
                idCurso);
    }

    @Override
    public Set<Integer> inscripcionesActivas(Integer idCurso) {
        return new HashSet<>(jdbc.query(
                "SELECT id_inscripcion FROM inscripcion WHERE id_curso = ? AND estado_inscripcion = 'ACTIVA'",
                (rs, i) -> rs.getInt(1), idCurso));
    }

    // ---------------- Asistencia ----------------

    @Override
    public List<AsistenciaAlumno> asistencia(Integer idCurso, LocalDate fecha) {
        return jdbc.query("""
                SELECT i.id_inscripcion, u.nombre_completo, a.estado_asistencia, a.observacion
                FROM inscripcion i
                JOIN usuario u ON u.id_usuario = i.id_usuario
                LEFT JOIN asistencia a ON a.id_inscripcion = i.id_inscripcion AND a.fecha = ?
                WHERE i.id_curso = ? AND i.estado_inscripcion = 'ACTIVA'
                ORDER BY u.nombre_completo
                """,
                (rs, i) -> new AsistenciaAlumno(rs.getInt("id_inscripcion"), rs.getString("nombre_completo"),
                        rs.getString("estado_asistencia"), rs.getString("observacion")),
                Date.valueOf(fecha), idCurso);
    }

    @Override
    public void guardarAsistencia(Integer idInscripcion, LocalDate fecha, String estado, String observacion) {
        jdbc.update("""
                INSERT INTO asistencia (id_inscripcion, fecha, observacion, estado_asistencia)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (id_inscripcion, fecha)
                DO UPDATE SET observacion = EXCLUDED.observacion, estado_asistencia = EXCLUDED.estado_asistencia
                """, idInscripcion, Date.valueOf(fecha), observacion, estado);
    }

    // ---------------- Evaluaciones ----------------

    @Override
    public List<EvaluacionDocente> evaluaciones(Integer idCurso) {
        return jdbc.query(SQL_EVALUACION + """
                        WHERE c.id_curso = ? AND i.estado_inscripcion = 'ACTIVA'
                        ORDER BY e.fecha_evaluacion DESC NULLS LAST, e.nombre_evaluacion, u.nombre_completo
                        """,
                (rs, i) -> aEvaluacion(rs), idCurso);
    }

    @Override
    public boolean existeEvaluacion(Integer idCurso, String nombre) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM evaluacion e JOIN inscripcion i ON i.id_inscripcion = e.id_inscripcion
                WHERE i.id_curso = ? AND lower(e.nombre_evaluacion) = lower(?)
                """, Integer.class, idCurso, nombre);
        return n != null && n > 0;
    }

    @Override
    public int crearEvaluacion(Integer idCurso, String nombre, LocalDate fecha) {
        // Los CAST permiten pasar la fecha vacía (null) sin que PostgreSQL pierda el tipo del parámetro
        return jdbc.update("""
                INSERT INTO evaluacion (id_inscripcion, nombre_evaluacion, nota, fecha_evaluacion)
                SELECT i.id_inscripcion, CAST(? AS VARCHAR), NULL, CAST(? AS DATE)
                FROM inscripcion i
                WHERE i.id_curso = ? AND i.estado_inscripcion = 'ACTIVA'
                """, nombre, fecha == null ? null : Date.valueOf(fecha), idCurso);
    }

    @Override
    public Optional<EvaluacionDocente> buscarEvaluacionDeDocente(Integer idDocente, Integer idEvaluacion) {
        return jdbc.query(SQL_EVALUACION + " WHERE e.id_evaluacion = ? AND c.id_usuario = ?",
                (rs, i) -> aEvaluacion(rs), idEvaluacion, idDocente).stream().findFirst();
    }

    private EvaluacionDocente aEvaluacion(ResultSet rs) throws SQLException {
        Date fecha = rs.getDate("fecha_evaluacion");
        return new EvaluacionDocente(rs.getInt("id_evaluacion"), rs.getInt("id_inscripcion"),
                rs.getString("nombre_completo"), rs.getString("nombre_evaluacion"), rs.getBigDecimal("nota"),
                fecha == null ? null : fecha.toLocalDate(), rs.getString("comentario"));
    }

    @Override
    public void actualizarEvaluacion(Integer idEvaluacion, BigDecimal nota, String comentario) {
        jdbc.update("UPDATE evaluacion SET nota = ?, comentario = ? WHERE id_evaluacion = ?",
                nota, comentario, idEvaluacion);
    }

    // ---------------- Material de apoyo ----------------

    @Override
    public List<MaterialDocente> material(Integer idDocente) {
        return jdbc.query(SQL_MATERIAL + " WHERE m.id_usuario = ? ORDER BY m.activo DESC, m.fecha_subida DESC",
                (rs, i) -> aMaterial(rs), idDocente);
    }

    @Override
    public Optional<MaterialDocente> buscarMaterialDeDocente(Integer idDocente, Integer idMaterial) {
        return jdbc.query(SQL_MATERIAL + " WHERE m.id_material = ? AND m.id_usuario = ?",
                (rs, i) -> aMaterial(rs), idMaterial, idDocente).stream().findFirst();
    }

    private MaterialDocente aMaterial(ResultSet rs) throws SQLException {
        return new MaterialDocente(rs.getInt("id_material"), rs.getInt("id_curso"), rs.getString("nombre_curso"),
                rs.getString("titulo"), rs.getString("descripcion"), rs.getString("tipo_material"),
                rs.getString("url_archivo"), rs.getTimestamp("fecha_subida").toLocalDateTime(),
                rs.getBoolean("activo"));
    }

    @Override
    public Integer insertarMaterial(Integer idDocente, Integer idCurso, String titulo, String descripcion,
                                    String tipo, String url) {
        return jdbc.queryForObject("""
                INSERT INTO material_apoyo (id_curso, id_usuario, titulo, descripcion, tipo_material, url_archivo)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id_material
                """, Integer.class, idCurso, idDocente, titulo, descripcion, tipo, url);
    }

    @Override
    public void actualizarActivoMaterial(Integer idMaterial, boolean activo) {
        jdbc.update("UPDATE material_apoyo SET activo = ? WHERE id_material = ?", activo, idMaterial);
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
