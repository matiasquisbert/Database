package com.academia.academia.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();   // costo 10, igual que los hashes de la base de datos
    }

    /** La sesión del usuario se guarda en el servidor (cookie JSESSIONID). */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityContextRepository securityContextRepository) throws Exception {
        http
                .cors(Customizer.withDefaults())
                // La cookie de sesión es SameSite=Lax (ver application.yaml): otros sitios no pueden enviarla en POST
                .csrf(csrf -> csrf.disable())
                .securityContext(contexto -> contexto.securityContextRepository(securityContextRepository))
                // Sin sesión: responde 401 (el frontend abre entonces la ventana de registro)
                .exceptionHandling(errores -> errores.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/logout").permitAll()
                        .requestMatchers("/api/health", "/api/cursos").permitAll()
                        // Panel de administración (de lo más específico a lo más general)
                        .requestMatchers(HttpMethod.PATCH, "/api/admin/usuarios/*/estado").hasRole("DIRECTOR")
                        .requestMatchers("/api/admin/usuarios/**", "/api/admin/pagos-pendientes", "/api/admin/cuotas-vencidas")
                                .hasAnyRole("DIRECTOR", "SECRETARIA")
                        .requestMatchers("/api/admin/**").hasAnyRole("DIRECTOR", "SECRETARIA", "RECEPCIONISTA")
                        // Panel del estudiante: solo el rol ESTUDIANTE (cada consulta se filtra por su propia sesión)
                        .requestMatchers("/api/estudiante/**").hasRole("ESTUDIANTE")
                        // Panel del docente: solo el rol DOCENTE (cada operación se limita a sus propios cursos)
                        .requestMatchers("/api/docente/**").hasRole("DOCENTE")
                        .requestMatchers("/api/usuarios/**").hasAnyRole("DIRECTOR", "SECRETARIA")
                        .anyRequest().authenticated());
        return http.build();
    }

    /** Permite que el frontend (Vite, puerto 5173) llame a la API. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:5173"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
