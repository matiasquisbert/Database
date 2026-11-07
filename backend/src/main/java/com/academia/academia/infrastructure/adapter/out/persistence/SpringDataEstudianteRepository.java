package com.academia.academia.infrastructure.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataEstudianteRepository extends JpaRepository<EstudianteJpaEntity, Integer> {

    // INSERT directo: la clave de estudiante es la misma del usuario (no se genera)
    @Modifying
    @Query(value = "INSERT INTO estudiante (id_usuario) VALUES (:idUsuario)", nativeQuery = true)
    void insertar(@Param("idUsuario") Integer idUsuario);
}
