package pe.edu.unmsm.lsdfms.generator;

import pe.edu.unmsm.lsdfms.model.InsurancePolicy;

import java.time.LocalDate;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * Fabrica reproducible de polizas sinteticas con campos de longitud variable.
 *
 * <p>El mismo seed produce exactamente la misma secuencia de polizas. Los DNI
 * se generan de forma determinista y sin colisiones para los volumenes del
 * proyecto (hasta 2 millones de registros).</p>
 */
public final class PolicyFactory {

    private static final String[] NOMBRES = {
            "Ana", "Luis", "Maria", "Carlos", "Rosa", "Jorge", "Lucia", "Miguel",
            "Elena", "Jose", "Patricia", "Diego", "Sofia", "Ricardo", "Carmen",
            "Fernando", "Gabriela", "Alberto", "Valeria", "Manuel", "Isabel",
            "Raul", "Monica", "Victor", "Teresa", "Andres", "Claudia", "Pablo",
            "Veronica", "Sergio", "Natalia", "Oscar", "Silvia", "Hector", "Paola",
            "Ramon", "Yolanda", "Julio", "Beatriz", "Cesar", "Alicia", "Marco",
            "Gloria", "Hugo", "Elsa", "Pedro", "Rita", "Juan", "Olga", "Eduardo"
    };

    private static final String[] APELLIDOS = {
            "Quispe", "Flores", "Sánchez", "Rojas", "Mendoza", "Vargas", "Castillo",
            "Ramírez", "Torres", "Chávez", "Ramos", "García", "Salazar", "Vega",
            "Paredes", "Cruz", "Huamán", "Ríos", "Gutiérrez", "Cárdenas", "Silva",
            "Espinoza", "Medina", "Palacios", "Campos", "Rivera", "Ortiz", "Núñez",
            "Cabrera", "Reyes", "Aguilar", "Delgado", "Herrera", "Cordero", "Ponce",
            "Acosta", "Valdivia", "Tello", "Zamora", "Bautista", "Soto", "Lozano",
            "Cabanillas", "Villanueva", "Mamani", "Condori", "Apaza", "Choque"
    };

    private static final String[] DEPARTAMENTOS = {
            "Lima", "Arequipa", "Cusco", "La Libertad", "Piura", "Junín",
            "Lambayeque", "Puno", "Ica", "Áncash", "Tacna", "Ucayali",
            "San Martín", "Huánuco", "Cajamarca", "Amazonas", "Ayacucho",
            "Moquegua", "Pasco", "Tumbes", "Loreto", "Madre de Dios"
    };

    private static final String[] TIPOS_SEGURO = {
            "Salud Integral", "Vehicular Todo Riesgo", "Vida Individual",
            "Accidentes Personales", "Hogar Protegido", "Viajes Internacional",
            "Salud Oncológica", "Responsabilidad Civil", "Renta Vitalicia",
            "Invalidez y Sobrevivencia"
    };

    private static final String[] SUFIJOS = {
            "", " Plus", " Premium", " Familiar", " Ejecutivo"
    };

    private final SplittableRandom random;

    public PolicyFactory(long seed) {
        this.random = new SplittableRandom(seed);
    }

    /**
     * Genera la poliza numero {@code index} (base cero). El id es
     * {@code index + 1} para que sea unico y facil de verificar.
     */
    public InsurancePolicy next(long index) {
        long id = index + 1;
        String dni = dniFor(index);
        String titular = nombreCompleto();
        String tipoSeguro = TIPOS_SEGURO[random.nextInt(TIPOS_SEGURO.length)]
                + SUFIJOS[random.nextInt(SUFIJOS.length)];
        String departamento = DEPARTAMENTOS[random.nextInt(DEPARTAMENTOS.length)];
        double prima = Math.round((500.0 + random.nextDouble() * 9_500.0) * 100.0) / 100.0;
        String fechaEmision = fechaEmision();
        return new InsurancePolicy(id, dni, titular, tipoSeguro, departamento, prima, fechaEmision);
    }

    private String nombreCompleto() {
        StringBuilder nombre = new StringBuilder();
        nombre.append(NOMBRES[random.nextInt(NOMBRES.length)]);
        if (random.nextBoolean()) {
            nombre.append(' ').append(NOMBRES[random.nextInt(NOMBRES.length)]);
        }
        nombre.append(' ')
                .append(APELLIDOS[random.nextInt(APELLIDOS.length)])
                .append(' ')
                .append(APELLIDOS[random.nextInt(APELLIDOS.length)]);
        return nombre.toString();
    }

    private String fechaEmision() {
        LocalDate start = LocalDate.of(2020, 1, 1);
        return start.plusDays(random.nextInt(2_557)).toString(); // hasta 2026-12-31
    }

    /**
     * DNI de 8 digitos, unico para los volumenes del proyecto. La multiplicacion
     * por 7919 (coprima con 90,000,000) genera una permutacion antes del modulo.
     */
    static String dniFor(long index) {
        long value = 10_000_000L + Math.floorMod(index * 7_919L, 90_000_000L);
        return String.format("%08d", value);
    }

    public static int catalogSize(String catalog) {
        Objects.requireNonNull(catalog, "catalog");
        return switch (catalog) {
            case "nombres" -> NOMBRES.length;
            case "apellidos" -> APELLIDOS.length;
            case "departamentos" -> DEPARTAMENTOS.length;
            case "tiposSeguro" -> TIPOS_SEGURO.length;
            default -> throw new IllegalArgumentException("Catalogo desconocido: " + catalog);
        };
    }
}
