package src.modelo;

// Tabla INSCRIPCION: PREINSCRITO -> INSCRITO (FORMINS) -> ABANDONO | RETIRADO (ESTINS) | CONCLUIDO
public class Inscripcion {
    private int idInscripcion;
    private String estado = "PREINSCRITO";
    private int idEstudiante;
    private int idOferta;
    private Integer idConvenio;

    public Inscripcion() {
    }

    public Inscripcion(int idEstudiante, int idOferta) {
        this.idEstudiante = idEstudiante;
        this.idOferta = idOferta;
    }

    public int getIdInscripcion() { return idInscripcion; }
    public void setIdInscripcion(int idInscripcion) { this.idInscripcion = idInscripcion; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public int getIdEstudiante() { return idEstudiante; }
    public void setIdEstudiante(int idEstudiante) { this.idEstudiante = idEstudiante; }
    public int getIdOferta() { return idOferta; }
    public void setIdOferta(int idOferta) { this.idOferta = idOferta; }
    public Integer getIdConvenio() { return idConvenio; }
    public void setIdConvenio(Integer idConvenio) { this.idConvenio = idConvenio; }
}
