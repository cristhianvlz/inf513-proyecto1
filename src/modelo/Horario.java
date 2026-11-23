package src.modelo;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

// Tabla HORARIO: franja semanal de una asignación; de ella se generan las SESION_CLASE
public class Horario {
    public static final List<String> DIAS = List.of("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES",
            "SABADO", "DOMINGO");

    private int idHorario;
    private String diaSemana;
    private LocalTime horaInicio;
    private LocalTime horaFin;
    private String aula;
    private int idAsignacion;

    public Horario() {
    }

    public Horario(Horario otro) {
        this.idHorario = otro.idHorario;
        this.diaSemana = otro.diaSemana;
        this.horaInicio = otro.horaInicio;
        this.horaFin = otro.horaFin;
        this.aula = otro.aula;
        this.idAsignacion = otro.idAsignacion;
    }

    public int getIdHorario() { return idHorario; }
    public void setIdHorario(int idHorario) { this.idHorario = idHorario; }
    public String getDiaSemana() { return diaSemana; }
    public void setDiaSemana(String diaSemana) { this.diaSemana = diaSemana; }
    public LocalTime getHoraInicio() { return horaInicio; }
    public void setHoraInicio(LocalTime horaInicio) { this.horaInicio = horaInicio; }
    public LocalTime getHoraFin() { return horaFin; }
    public void setHoraFin(LocalTime horaFin) { this.horaFin = horaFin; }
    public String getAula() { return aula; }
    public void setAula(String aula) { this.aula = aula; }
    public int getIdAsignacion() { return idAsignacion; }
    public void setIdAsignacion(int idAsignacion) { this.idAsignacion = idAsignacion; }

    // LUNES -> MONDAY (ISO: 1), ... DOMINGO -> SUNDAY (7)
    public DayOfWeek getDiaDeLaSemana() {
        return DayOfWeek.of(DIAS.indexOf(diaSemana) + 1);
    }

    public String getDescripcion() {
        return diaSemana + " " + horaInicio + "-" + horaFin + (aula != null ? " (" + aula + ")" : "");
    }
}
