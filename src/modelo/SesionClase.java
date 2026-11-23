package src.modelo;

import java.time.LocalDate;
import java.time.LocalTime;

// Tabla SESION_CLASE: PROGRAMADA -> DICTADA | SUSPENDIDA. Una clase de recuperación (ADDSES) nace RECUPERADA
public class SesionClase {
    private int idSesion;
    private LocalDate fecha;
    private LocalTime horaInicio;
    private LocalTime horaFin;
    private String estado;
    private int idAsignacion;

    public int getIdSesion() { return idSesion; }
    public void setIdSesion(int idSesion) { this.idSesion = idSesion; }
    public LocalDate getFecha() { return fecha; }
    public void setFecha(LocalDate fecha) { this.fecha = fecha; }
    public LocalTime getHoraInicio() { return horaInicio; }
    public void setHoraInicio(LocalTime horaInicio) { this.horaInicio = horaInicio; }
    public LocalTime getHoraFin() { return horaFin; }
    public void setHoraFin(LocalTime horaFin) { this.horaFin = horaFin; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public int getIdAsignacion() { return idAsignacion; }
    public void setIdAsignacion(int idAsignacion) { this.idAsignacion = idAsignacion; }

    // Una clase que se llevó a cabo: es la que cuenta para el porcentaje de asistencia
    public boolean fueDada() {
        return ("DICTADA".equals(estado) || "RECUPERADA".equals(estado)) && !fecha.isAfter(LocalDate.now());
    }

    public String getDescripcion() {
        return fecha + " " + horaInicio;
    }
}
