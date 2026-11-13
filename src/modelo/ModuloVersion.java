package src.modelo;

// Tabla MODULO_VERSION: un módulo dentro de la malla de una versión, con su orden y carga horaria
public class ModuloVersion {
    private int idModuloVersion;
    private int ordenModulo;
    private int cargaHoraria;
    private int idModulo;
    private int idVersion;

    public ModuloVersion() {
    }

    public ModuloVersion(int idVersion, int idModulo, int ordenModulo, int cargaHoraria) {
        this.idVersion = idVersion;
        this.idModulo = idModulo;
        this.ordenModulo = ordenModulo;
        this.cargaHoraria = cargaHoraria;
    }

    public int getIdModuloVersion() { return idModuloVersion; }
    public void setIdModuloVersion(int idModuloVersion) { this.idModuloVersion = idModuloVersion; }
    public int getOrdenModulo() { return ordenModulo; }
    public void setOrdenModulo(int ordenModulo) { this.ordenModulo = ordenModulo; }
    public int getCargaHoraria() { return cargaHoraria; }
    public void setCargaHoraria(int cargaHoraria) { this.cargaHoraria = cargaHoraria; }
    public int getIdModulo() { return idModulo; }
    public void setIdModulo(int idModulo) { this.idModulo = idModulo; }
    public int getIdVersion() { return idVersion; }
    public void setIdVersion(int idVersion) { this.idVersion = idVersion; }
}
