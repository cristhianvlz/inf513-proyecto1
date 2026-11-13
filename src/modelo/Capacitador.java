package src.modelo;

// Tabla CAPACITOR: perfil académico de un PERSONAL que dicta módulos
public class Capacitador extends Personal {
    private String tituloAcademico;
    private String gradoAcademico;
    private String experienciaLab;
    private String cvDocumento;

    public Capacitador() {
    }

    public Capacitador(String ci, String ciExpedicion, String nombre, String apellidos, String direccion,
            String telefono, String tituloAcademico, String gradoAcademico, String experienciaLab,
            String cvDocumento) {
        super(ci, ciExpedicion, nombre, apellidos, direccion, telefono);
        this.tituloAcademico = tituloAcademico;
        this.gradoAcademico = gradoAcademico;
        this.experienciaLab = experienciaLab;
        this.cvDocumento = cvDocumento;
    }

    public String getTituloAcademico() { return tituloAcademico; }
    public void setTituloAcademico(String tituloAcademico) { this.tituloAcademico = tituloAcademico; }
    public String getGradoAcademico() { return gradoAcademico; }
    public void setGradoAcademico(String gradoAcademico) { this.gradoAcademico = gradoAcademico; }
    public String getExperienciaLab() { return experienciaLab; }
    public void setExperienciaLab(String experienciaLab) { this.experienciaLab = experienciaLab; }
    public String getCvDocumento() { return cvDocumento; }
    public void setCvDocumento(String cvDocumento) { this.cvDocumento = cvDocumento; }
}
