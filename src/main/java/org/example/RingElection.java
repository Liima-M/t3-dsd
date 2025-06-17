import java.io.*;
import java.net.*;
import java.util.Random;
import java.util.Scanner;

/**
 * Implementação do algoritmo de eleição em anel (Chang e Roberts)
 * com geração automática de IDs e suporte a conexões por IP
 */
public class RingElection {
    private final int nodeId;        // ID único gerado automaticamente
    private final int port;          // Porta local para escutar conexões
    private final String nextHost;   // IP/hostname do próximo nó
    private final int nextPort;      // Porta do próximo nó
    private Integer leader = null;   // ID do líder atual (null se não houver)
    private boolean electionInProgress = false; // Flag de eleição
    private ServerSocket serverSocket; // Socket servidor
    private static final Random random = new Random(); // Gerador de IDs

    /**
     * Construtor - configura o nó com porta e próximo destino
     * @param port Porta local para escutar conexões
     * @param nextHost IP/hostname do próximo nó no anel
     * @param nextPort Porta do próximo nó no anel
     */
    public RingElection(int port, String nextHost, int nextPort) {
        this.nodeId = generateRandomId();
        this.port = port;
        this.nextHost = nextHost;
        this.nextPort = nextPort;
    }

    /**
     * Gera um ID aleatório entre 1000 e 9999
     * @return ID único para o nó
     */
    private int generateRandomId() {
        return 1000 + random.nextInt(9000); // Gera ID de 4 dígitos
    }

    /**
     * Valida se um endereço de rede (IP/hostname) é válido
     * @param address Endereço a validar
     * @return true se válido, false caso contrário
     */
    private boolean isValidAddress(String address) {
        try {
            InetAddress.getByName(address);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * Inicia o nó:
     * 1. Valida o próximo endereço
     * 2. Inicia o servidor em thread separada
     * 3. Mostra menu interativo
     * Saída esperada:
     * - Mensagem com ID gerado
     * - Mensagens de erro se endereço inválido
     */
    public void start() {
        if (!isValidAddress(nextHost)) {
            System.err.println("Erro: Endereço inválido para próximo nó: " + nextHost);
            System.exit(1);
        }

        System.out.printf("Nó iniciado com ID: %d\n", nodeId);
        new Thread(this::runServer).start(); // Thread do servidor
        runMenu(); // Menu na thread principal
    }

    /**
     * Servidor que escuta por conexões de outros nós
     * Saída esperada:
     * - Mensagem indicando porta em escuta
     * - Mensagens de conexões recebidas
     * - Erros se houver problemas no socket
     */
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

    /**
     * Processa uma conexão de cliente recebida
     * @param clientSocket Socket do cliente conectado
     * Saída esperada:
     * - Mensagem com conteúdo recebido
     * - Erros de comunicação
     * - Chama processMessage para tratar o conteúdo
     */
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

    /**
     * Processa mensagens recebidas (ELECTION ou LEADER)
     * @param message Mensagem no formato "TIPO ID"
     * Saída esperada:
     * - Mensagens de decisão tomada (repassar, substituir, eleger)
     * - Anúncio quando eleito líder
     * - Mensagens de erro se formato inválido
     */
    private void processMessage(String message) {
        String[] parts = message.split(" ");
        if (parts.length < 2) {
            System.err.println("Mensagem inválida recebida: " + message);
            return;
        }

        String type = parts[0];
        int receivedId = Integer.parseInt(parts[1]);

        switch (type) {
            case "ELECTION":
                if (receivedId > nodeId) {
                    sendMessage(message); // Repassa ID maior
                } else if (receivedId < nodeId) {
                    if (!electionInProgress) {
                        startElection(); // Inicia com próprio ID
                    }
                } else {
                    becomeLeader(); // Eleito líder
                }
                break;

            case "LEADER":
                leader = receivedId;
                electionInProgress = false;
                System.out.printf("\nLíder eleito: Nó %d\n", leader);
                if (nodeId != leader) {
                    sendMessage(message); // Repassa anúncio
                }
                break;

            default:
                System.err.println("Tipo de mensagem desconhecido: " + type);
        }
    }

    /**
     * Envia mensagem para o próximo nó no anel
     * @param message Mensagem a ser enviada
     * Saída esperada:
     * - Confirmação de envio com destino
     * - Mensagens de erro se não conseguir enviar
     * - Tentativa de reconexão após 5 segundos em caso de falha
     */
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
                sendMessage(message); // Tentativa recursiva
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Inicia processo de eleição
     * Saída esperada:
     * - Mensagem indicando início da eleição
     * - Envio de mensagem ELECTION com próprio ID
     */
    public void startElection() {
        if (!electionInProgress) {
            System.out.printf("\nNó %d iniciando eleição...\n", nodeId);
            electionInProgress = true;
            sendMessage("ELECTION " + nodeId);
        }
    }

    /**
     * Procedimento quando o nó é eleito líder
     * Saída esperada:
     * - Mensagem especial de eleição
     * - Envio de mensagem LEADER para todos
     */
    private void becomeLeader() {
        leader = nodeId;
        electionInProgress = false;
        System.out.printf("\n>>> Nó %d ELEITO LÍDER! <<<\n", nodeId);
        sendMessage("LEADER " + leader);
    }

    /**
     * Menu interativo para controle do nó
     * Saída esperada:
     * - Menu com opções exibido no console
     * - Resultados das ações selecionadas
     */
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

    /**
     * Exibe status completo do nó
     * Saída esperada:
     * - ID, porta, próximo nó
     * - Líder atual e status de eleição
     * - Endereço IP local
     */
    private void printStatus() {
        System.out.printf("\nStatus do Nó %d:\n", nodeId);
        System.out.printf(" - Porta: %d\n", port);
        System.out.printf(" - Próximo nó: %s:%d\n", nextHost, nextPort);
        System.out.printf(" - Líder atual: %s\n", leader != null ? "Nó " + leader : "Nenhum");
        System.out.printf(" - Eleição em andamento: %b\n", electionInProgress);

        try {
            System.out.printf(" - Endereço IP: %s\n", InetAddress.getLocalHost().getHostAddress());
        } catch (UnknownHostException e) {
            System.err.println(" - Não foi possível obter o endereço IP local");
        }
    }

    /**
     * Encerra o nó de forma segura
     * Saída esperada:
     * - Mensagem de encerramento
     * - Fechamento do socket servidor
     */
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

    /**
     * Ponto de entrada do programa
     * @param args [0] porta local, [1] próximo host, [2] próxima porta
     * Saída esperada:
     * - Mensagem de uso se argumentos faltando
     * - Inicialização do nó com os parâmetros fornecidos
     */
    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Uso: java RingElection <PORTA> <PRÓXIMO_HOST> <PRÓXIMA_PORTA>");
            System.out.println("Exemplo: java RingElection 5001 192.168.1.2 5002");
            System.exit(1);
        }

        try {
            int port = Integer.parseInt(args[0]);
            String nextHost = args[1];
            int nextPort = Integer.parseInt(args[2]);

            new RingElection(port, nextHost, nextPort).start();
        } catch (NumberFormatException e) {
            System.err.println("Erro: Porta deve ser um número válido");
            System.exit(1);
        }
    }
}