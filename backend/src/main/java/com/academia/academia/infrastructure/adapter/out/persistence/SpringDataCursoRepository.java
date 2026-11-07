package com.academia.academia.infrastructure.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataCursoRepository extends JpaRepository<CursoJpaEntity, Integer> {

    List<CursoJpaEntity> findByActivoTrueOrderByNombreCursoAsc();

    Optional<CursoJpaEntity> findByIdAndActivoTrue(Integer id);
}
