package com.academia.academia.infrastructure.adapter.in.web;

import com.academia.academia.domain.model.EstudiantePanel;
import com.academia.academia.domain.port.in.EstudianteCasosDeUso;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Panel del estudiante. Solo entra el rol ESTUDIANTE (ver SecurityConfig) y cada consulta
 * se hace con el correo de la sesión, así que un estudiante nunca ve datos de otro.
 */
@RestController
@RequestMapping("/api/estudiante")
public class EstudianteController {

    private final EstudianteCasosDeUso estudiante;

    public EstudianteController(EstudianteCasosDeUso estudiante) {
        this.estudiante = estudiante;
    }

    @GetMapping("/resumen")
    public EstudiantePanel.Resumen resumen(Authentication auth) {
        return estudiante.resumen(auth.getName());
    }

    @GetMapping("/cursos")
    public List<EstudiantePanel.CursoEstudiante> cursos(Authentication auth) {
        return estudiante.misCursos(auth.getName());
    }

    @GetMapping("/cuotas")
    public List<EstudiantePanel.CuotaEstudiante> cuotas(Authentication auth) {
        return estudiante.misCuotas(auth.getName());
    }

    @GetMapping("/asistencia")
    public List<EstudiantePanel.AsistenciaEstudiante> asistencia(Authentication auth) {
        return estudiante.miAsistencia(auth.getName());
    }

    @GetMapping("/evaluaciones")
    public List<EstudiantePanel.EvaluacionEstudiante> evaluaciones(Authentication auth) {
        return estudiante.misEvaluaciones(auth.getName());
    }

    @GetMapping("/material")
    public List<EstudiantePanel.MaterialEstudiante> material(Authentication auth) {
        return estudiante.miMaterial(auth.getName());
    }

    @GetMapping("/prestamos")
    public List<EstudiantePanel.PrestamoEstudiante> prestamos(Authentication auth) {
        return estudiante.misPrestamos(auth.getName());
    }

    @GetMapping("/eventos")
    public List<EstudiantePanel.EventoEstudiante> eventos(Authentication auth) {
        return estudiante.eventos(auth.getName());
    }
}
