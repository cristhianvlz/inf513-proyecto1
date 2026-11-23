package src.modelo;

// Tabla PERSONAL: personal administrativo y base de los capacitadores
public class Personal {
    private int idPersonal;
    private String nombre;
    private String apellidos;
    private String ci;
    private String ciExpedicion;
    private String direccion;
    private String telefono;
    private String estado = "ACTIVO";

    public Personal() {
    }

    public Personal(String ci, String ciExpedicion, String nombre, String apellidos, String direccion,
            String telefono) {
        this.ci = ci;
        this.ciExpedicion = ciExpedicion;
        this.nombre = nombre;
        this.apellidos = apellidos;
        this.direccion = direccion;
        this.telefono = telefono;
    }

    public int getIdPersonal() { return idPersonal; }
    public void setIdPersonal(int idPersonal) { this.idPersonal = idPersonal; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public String getApellidos() { return apellidos; }
    public void setApellidos(String apellidos) { this.apellidos = apellidos; }
    public String getCi() { return ci; }
    public void setCi(String ci) { this.ci = ci; }
    public String getCiExpedicion() { return ciExpedicion; }
    public void setCiExpedicion(String ciExpedicion) { this.ciExpedicion = ciExpedicion; }
    public String getDireccion() { return direccion; }
    public void setDireccion(String direccion) { this.direccion = direccion; }
    public String getTelefono() { return telefono; }
    public void setTelefono(String telefono) { this.telefono = telefono; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    public String getNombreCompleto() {
        return nombre + " " + apellidos;
    }
}
