package com.academia.academia.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "curso")
public class CursoJpaEntity {

    @Id
    @Column(name = "id_curso")
    private Integer id;

    // docente que dicta el curso (curso.id_usuario)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_usuario", nullable = false)
    private UsuarioJpaEntity docente;

    @Column(name = "nombre_curso", nullable = false)
    private String nombreCurso;

    @Column(name = "activo", nullable = false)
    private Boolean activo;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public UsuarioJpaEntity getDocente() { return docente; }
    public void setDocente(UsuarioJpaEntity docente) { this.docente = docente; }
    public String getNombreCurso() { return nombreCurso; }
    public void setNombreCurso(String nombreCurso) { this.nombreCurso = nombreCurso; }
    public Boolean getActivo() { return activo; }
    public void setActivo(Boolean activo) { this.activo = activo; }
}
