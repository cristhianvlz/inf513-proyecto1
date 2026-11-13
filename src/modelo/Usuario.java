package src.modelo;

// Tabla USUARIO: credenciales del personal. El email es la dirección desde la que envía sus comandos
public class Usuario {
    private int idUsuario;
    private String usuario;
    private String clave;
    private String email;
    private String estado = "ACTIVO";
    private int idPersonal;

    public Usuario() {
    }

    public Usuario(String usuario, String clave, String email, int idPersonal) {
        this.usuario = usuario;
        this.clave = clave;
        this.email = email;
        this.idPersonal = idPersonal;
    }

    public int getIdUsuario() { return idUsuario; }
    public void setIdUsuario(int idUsuario) { this.idUsuario = idUsuario; }
    public String getUsuario() { return usuario; }
    public void setUsuario(String usuario) { this.usuario = usuario; }
    public String getClave() { return clave; }
    public void setClave(String clave) { this.clave = clave; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public int getIdPersonal() { return idPersonal; }
    public void setIdPersonal(int idPersonal) { this.idPersonal = idPersonal; }

    public boolean estaActivo() {
        return "ACTIVO".equalsIgnoreCase(estado);
    }
}
