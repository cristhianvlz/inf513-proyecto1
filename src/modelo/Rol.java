package src.modelo;

// Tabla ROL: los roles y sus permisos se cargan en el despliegue inicial
public class Rol {
    private int idRol;
    private String nombre;
    private String descripcion;

    public Rol(int idRol, String nombre, String descripcion) {
        this.idRol = idRol;
        this.nombre = nombre;
        this.descripcion = descripcion;
    }

    public int getIdRol() { return idRol; }
    public String getNombre() { return nombre; }
    public String getDescripcion() { return descripcion; }
}
