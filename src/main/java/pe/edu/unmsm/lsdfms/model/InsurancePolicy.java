package pe.edu.unmsm.lsdfms.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Entidad de Negocio: Póliza de Seguro (InsurancePolicy).
 * Cumple con RNF-05 (caso de negocio realista) y RF-01 (campos y longitud variable).
 */
public class InsurancePolicy implements Record, Serializable {
    private static final long serialVersionUID = 1L;

    private boolean deleted;
    private long id;
    private String dni;            // Longitud fija ~8 caracteres
    private String titular;        // Longitud variable (nombres y apellidos)
    private String tipoSeguro;     // Longitud variable (ej. "Salud Integral", "Vehicular Todo Riesgo")
    private String departamento;   // Longitud variable (ej. "Lima", "Arequipa", "La Libertad")
    private double prima;          // Monto numérico (8 bytes)
    private String fechaEmision;   // Longitud variable (formato YYYY-MM-DD)

    public InsurancePolicy() {
        this.deleted = false;
    }

    public InsurancePolicy(long id, String dni, String titular, String tipoSeguro, 
                           String departamento, double prima, String fechaEmision) {
        this(false, id, dni, titular, tipoSeguro, departamento, prima, fechaEmision);
    }

    public InsurancePolicy(boolean deleted, long id, String dni, String titular, 
                           String tipoSeguro, String departamento, double prima, String fechaEmision) {
        this.deleted = deleted;
        this.id = id;
        this.dni = Objects.requireNonNull(dni, "DNI no puede ser nulo");
        this.titular = Objects.requireNonNull(titular, "Titular no puede ser nulo");
        this.tipoSeguro = Objects.requireNonNull(tipoSeguro, "Tipo de seguro no puede ser nulo");
        this.departamento = Objects.requireNonNull(departamento, "Departamento no puede ser nulo");
        this.prima = prima;
        this.fechaEmision = Objects.requireNonNull(fechaEmision, "Fecha de emision no puede ser nula");
    }

    @Override
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    @Override
    public boolean isDeleted() {
        return deleted;
    }

    @Override
    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public String getDni() {
        return dni;
    }

    public void setDni(String dni) {
        this.dni = dni;
    }

    public String getTitular() {
        return titular;
    }

    public void setTitular(String titular) {
        this.titular = titular;
    }

    public String getTipoSeguro() {
        return tipoSeguro;
    }

    public void setTipoSeguro(String tipoSeguro) {
        this.tipoSeguro = tipoSeguro;
    }

    public String getDepartamento() {
        return departamento;
    }

    public void setDepartamento(String departamento) {
        this.departamento = departamento;
    }

    public double getPrima() {
        return prima;
    }

    public void setPrima(double prima) {
        this.prima = prima;
    }

    public String getFechaEmision() {
        return fechaEmision;
    }

    public void setFechaEmision(String fechaEmision) {
        this.fechaEmision = fechaEmision;
    }

    @Override
    public String toString() {
        return "InsurancePolicy{" +
                "id=" + id +
                ", dni='" + dni + '\'' +
                ", titular='" + titular + '\'' +
                ", tipoSeguro='" + tipoSeguro + '\'' +
                ", departamento='" + departamento + '\'' +
                ", prima=" + String.format("%.2f", prima) +
                ", fechaEmision='" + fechaEmision + '\'' +
                ", deleted=" + deleted +
                '}';
    }
}
