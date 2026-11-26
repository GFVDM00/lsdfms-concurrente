package pe.edu.unmsm.lsdfms;

import pe.edu.unmsm.lsdfms.server.ServerApp;

/**
 * Punto de entrada principal del sistema LSDFMS.
 *
 * <p>Arranca el servidor HTTP nativo con el motor de almacenamiento, la cola
 * sincronizada, el motor de indices y el mantenimiento PACK/INDEX.</p>
 */
public class Main {
    public static void main(String[] args) {
        ServerApp.main(args);
    }
}
