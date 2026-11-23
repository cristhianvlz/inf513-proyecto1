package src.modelo;

import java.time.LocalDate;

// Tabla ESTUDIANTE: expediente sin cuenta de acceso; el email solo recibe notificaciones
public class Estudiante {
    private int idEstudiante;
    private String codigoEstudiante;
    private String nombre;
    private String paterno;
    private String materno;
    private String ci;
    private String ciExpedicion;
    private LocalDate fechaNacimiento;
    private String direccion;
    private String telefono;
    private String email;
    private String nivelEscolaridad;
    private String ciudad;
    private String pais = "Bolivia";
    private String estado = "ACTIVO";

    public int getIdEstudiante() { return idEstudiante; }
    public void setIdEstudiante(int idEstudiante) { this.idEstudiante = idEstudiante; }
    public String getCodigoEstudiante() { return codigoEstudiante; }
    public void setCodigoEstudiante(String codigoEstudiante) { this.codigoEstudiante = codigoEstudiante; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public String getPaterno() { return paterno; }
    public void setPaterno(String paterno) { this.paterno = paterno; }
    public String getMaterno() { return materno; }
    public void setMaterno(String materno) { this.materno = materno; }
    public String getCi() { return ci; }
    public void setCi(String ci) { this.ci = ci; }
    public String getCiExpedicion() { return ciExpedicion; }
    public void setCiExpedicion(String ciExpedicion) { this.ciExpedicion = ciExpedicion; }
    public LocalDate getFechaNacimiento() { return fechaNacimiento; }
    public void setFechaNacimiento(LocalDate fechaNacimiento) { this.fechaNacimiento = fechaNacimiento; }
    public String getDireccion() { return direccion; }
    public void setDireccion(String direccion) { this.direccion = direccion; }
    public String getTelefono() { return telefono; }
    public void setTelefono(String telefono) { this.telefono = telefono; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getNivelEscolaridad() { return nivelEscolaridad; }
    public void setNivelEscolaridad(String nivelEscolaridad) { this.nivelEscolaridad = nivelEscolaridad; }
    public String getCiudad() { return ciudad; }
    public void setCiudad(String ciudad) { this.ciudad = ciudad; }
    public String getPais() { return pais; }
    public void setPais(String pais) { this.pais = pais; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    // Hasta formalizar la inscripción (FORMINS) el código es provisional: PRE-<ci>
    public boolean tieneCodigoDefinitivo() {
        return codigoEstudiante != null && !codigoEstudiante.startsWith("PRE-");
    }

    public String getNombreCompleto() {
        return nombre + " " + paterno + (materno != null && !materno.isEmpty() ? " " + materno : "");
    }
}
