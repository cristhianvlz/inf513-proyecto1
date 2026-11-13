package src.comunicacion;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class ConsolaPruebas {
    public static void main(String[] args) {
        String servidor = "mail.tecnoweb.org.bo";
        String user_receptor = "grupo15sc@tecnoweb.org.bo";
        String user_emisor = "1711167cvr@gmail.com";
        int puerto = 25;

        Scanner scanner = new Scanner(System.in);
        System.out.println("==================================================");
        System.out.println("   CONSOLA INTERACTIVA DE PRUEBAS ICAP - TCP/SMTP ");
        System.out.println("==================================================");

        while (true) {
            System.out.print("\nEscribe el comando para el Subject (o escribe 'SALIR'):\n> ");
            String asuntoComando = scanner.nextLine();

            if (asuntoComando.equalsIgnoreCase("SALIR")) {
                System.out.println("Saliendo de la consola de pruebas...");
                break;
            }

            try (Socket socket = new Socket(servidor, puerto);
                    BufferedReader entrada = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    DataOutputStream salida = new DataOutputStream(socket.getOutputStream())) {

                entrada.readLine(); // Lee el 220 de bienvenida

                salida.writeBytes("HELO " + servidor + " \r\n");
                entrada.readLine();

                salida.writeBytes("MAIL FROM: <" + user_emisor + "> \r\n");
                entrada.readLine();

                salida.writeBytes("RCPT TO: <" + user_receptor + "> \r\n");
                entrada.readLine();

                salida.writeBytes("DATA \r\n");
                entrada.readLine();

                String mensaje = "From: <" + user_emisor + ">\r\n"
                        + "To: <" + user_receptor + ">\r\n"
                        + "Subject: " + asuntoComando + "\r\n\r\n"
                        + "Prueba desde consola interactiva.\r\n"
                        + ".\r\n";

                salida.write(mensaje.getBytes(StandardCharsets.UTF_8));
                entrada.readLine();

                salida.writeBytes("QUIT \r\n");
                entrada.readLine();

                System.out.println(">>> [ÉXITO]: Comando '" + asuntoComando + "' depositado en el servidor.");
                System.out.println(">>> Ahora ejecuta MainICAP.java para procesarlo.");

            } catch (Exception e) {
                System.out.println("Error de conexión SMTP: " + e.getMessage());
            }
        }
        scanner.close();
    }
}