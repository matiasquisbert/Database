package com.academia.academia.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalTime;

@Entity
@Table(name = "horario")
public class HorarioJpaEntity {

    @Id
    @Column(name = "id_horario")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_curso", nullable = false)
    private CursoJpaEntity curso;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_aula", nullable = false)
    private AulaJpaEntity aula;

    // LUNES, MARTES, MIERCOLES... (MAYÚSCULAS, sin tilde)
    @Column(name = "dia", nullable = false)
    private String dia;

    @Column(name = "hora_inicio", nullable = false)
    private LocalTime horaInicio;

    @Column(name = "hora_fin", nullable = false)
    private LocalTime horaFin;

    @Column(name = "activo", nullable = false)
    private Boolean activo;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public CursoJpaEntity getCurso() { return curso; }
    public void setCurso(CursoJpaEntity curso) { this.curso = curso; }
    public AulaJpaEntity getAula() { return aula; }
    public void setAula(AulaJpaEntity aula) { this.aula = aula; }
    public String getDia() { return dia; }
    public void setDia(String dia) { this.dia = dia; }
    public LocalTime getHoraInicio() { return horaInicio; }
    public void setHoraInicio(LocalTime horaInicio) { this.horaInicio = horaInicio; }
    public LocalTime getHoraFin() { return horaFin; }
    public void setHoraFin(LocalTime horaFin) { this.horaFin = horaFin; }
    public Boolean getActivo() { return activo; }
    public void setActivo(Boolean activo) { this.activo = activo; }
}
