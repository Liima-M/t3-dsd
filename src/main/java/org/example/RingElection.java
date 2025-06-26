package org.example;

import java.io.*;
import java.net.*;
import java.util.Scanner;

public class RingElection {
    private final int nodeId; // id do processo
    private final int port; // porta
    private final String nextHost; // host do próximo
    private final int nextPort; // porta do próximo
    private Integer coordenador = null; // id do coordenador atual
    private boolean electionInProgress = false;
    private ServerSocket serverSocket;

    public RingElection(int id, int port, String nextHost, int nextPort) {
        this.nodeId = id;
        this.port = port;
        this.nextHost = nextHost;
        this.nextPort = nextPort;
    }

    private boolean isValidAddress(String address) {
        try {
            InetAddress.getByName(address);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    public void start() {
        if (!isValidAddress(nextHost)) {
            System.err.println("Erro: Endereço inválido para próximo nó: " + nextHost);
            System.exit(1);
        }
        System.out.printf("Nó iniciado com ID: %d\n", nodeId);
        new Thread(this::runServer).start(); // Thread do servidor
        runMenu(); // Menu na thread principal
    }

    private void runServer() {
        try {
            serverSocket = new ServerSocket(port);
            System.out.printf("Nó %d ouvindo na porta %d\n", nodeId, port);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                String clientAddress = clientSocket.getInetAddress().getHostAddress();
                System.out.printf("Conexão recebida de %s\n", clientAddress);
                new Thread(() -> handleClient(clientSocket)).start();
            }
        } catch (IOException e) {
            System.err.println("Erro no servidor: " + e.getMessage());
        }
    }

    private void handleClient(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream()))) {

            String message = in.readLine();
            if (message == null) return;

            System.out.printf("Nó %d recebeu: %s\n", nodeId, message);
            processMessage(message);

        } catch (IOException e) {
            System.err.println("Erro ao lidar com cliente: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.err.println("Erro ao fechar socket: " + e.getMessage());
            }
        }
    }

    private void processMessage(String message) {
        String[] parts = message.split(" ");
        if (parts.length < 2) {
            System.err.println("Mensagem inválida recebida: " + message);
            return;
        }
        System.out.println("Mensagem recebida: " + message);
        String type = parts[0];
        int receivedId = Integer.parseInt(parts[1]);
        switch (type) {
            case "ELEICAO":
                if (receivedId > nodeId) {
                    sendMessage(message);
                } else if (receivedId < nodeId) {
                    if (!electionInProgress) {
                        startElection();
                    }
                } else {
                    becomeLeader();
                }
                break;

            case "COORDENADOR":
                coordenador = receivedId;
                electionInProgress = false;
                System.out.printf("\nCoordenador eleito: Nó %d\n", coordenador);
                if (nodeId != coordenador) {
                    sendMessage(message); // repassa
                }
                break;

            default:
                System.err.println("Tipo de mensagem desconhecido: " + type);
        }
    }

    private void sendMessage(String message) {
        try {
            InetAddress nextAddress = InetAddress.getByName(nextHost);
            try (Socket socket = new Socket(nextAddress, nextPort);
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
                out.println(message);
                System.out.printf("Nó %d enviou '%s' para %s:%d\n",
                        nodeId, message, nextAddress.getHostAddress(), nextPort);
            }
        } catch (UnknownHostException e) {
            System.err.printf("Host desconhecido: %s\n", nextHost);
        } catch (IOException e) {
            System.err.printf("Falha ao enviar para %s:%d: %s\n",
                    nextHost, nextPort, e.getMessage());
            System.out.println("Tentando reconectar em 5 segundos...");
            try {
                Thread.sleep(5000);
                sendMessage(message); // tenta de novo...
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void startElection() {
        if (!electionInProgress) {
            System.out.printf("\nNó %d iniciando eleição...\n", nodeId);
            electionInProgress = true;
            sendMessage("ELEICAO " + nodeId);
        }
    }

    private void becomeLeader() {
        coordenador = nodeId;
        electionInProgress = false;
        System.out.printf("\n>>> Nó %d ELEITO COORDENADOR! <<<\n", nodeId);
        sendMessage("COORDENADOR " + coordenador);
    }

    private void runMenu() {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.println("\nMenu:");
            System.out.println("1. Iniciar eleição");
            System.out.println("2. Ver status");
            System.out.println("3. Sair");
            System.out.print("Escolha: ");
            String choice = scanner.nextLine();
            switch (choice) {
                case "1":
                    startElection();
                    break;
                case "2":
                    printStatus();
                    break;
                case "3":
                    shutdown();
                    break;
                default:
                    System.out.println("Opção inválida");
            }
        }
    }

    private void printStatus() {
        System.out.printf("\nStatus do Nó %d:\n", nodeId);
        System.out.printf(" - Porta: %d\n", port);
        System.out.printf(" - Próximo nó: %s:%d\n", nextHost, nextPort);
        System.out.printf(" - Coordenador atual: %s\n", coordenador != null ? "Nó " + coordenador : "Nenhum");
        System.out.printf(" - Eleição em andamento: %b\n", electionInProgress);

        try {
            System.out.printf(" - Endereço IP: %s\n", InetAddress.getLocalHost().getHostAddress());
        } catch (UnknownHostException e) {
            System.err.println(" - Não foi possível obter o endereço IP local");
        }
    }

    private void shutdown() {
        System.out.println("Encerrando nó " + nodeId + "...");
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("Erro ao fechar servidor: " + e.getMessage());
        }
        System.exit(0);
    }

    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("Uso: <ID> <PORTA> <PRÓXIMO_HOST> <PRÓXIMA_PORTA>");
            System.out.println("Exemplo: 01 5001 192.168.1.2 5002");
            System.exit(1);
        }
        try {
            int idProcesso = Integer.parseInt(args[0]);
            int port = Integer.parseInt(args[1]);
            String nextHost = args[2];
            int nextPort = Integer.parseInt(args[3]);
            new RingElection(idProcesso, port, nextHost, nextPort).start();
        } catch (NumberFormatException e) {
            System.err.println("Erro: Porta deve ser um número válido");
            System.exit(1);
        }
    }
}