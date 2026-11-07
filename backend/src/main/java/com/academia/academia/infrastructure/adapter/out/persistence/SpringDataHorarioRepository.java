package com.academia.academia.infrastructure.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SpringDataHorarioRepository extends JpaRepository<HorarioJpaEntity, Integer> {

    @Query("select h from HorarioJpaEntity h "
            + "join fetch h.curso c "
            + "join fetch c.docente "
            + "join fetch h.aula "
            + "where h.activo = true and c.activo = true")
    List<HorarioJpaEntity> listarActivos();
}
