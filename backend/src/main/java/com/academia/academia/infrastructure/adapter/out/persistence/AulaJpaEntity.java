package com.academia.academia.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "aula")
public class AulaJpaEntity {

    @Id
    @Column(name = "id_aula")
    private Integer id;

    @Column(name = "nombre_aula", nullable = false)
    private String nombreAula;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getNombreAula() { return nombreAula; }
    public void setNombreAula(String nombreAula) { this.nombreAula = nombreAula; }
}
