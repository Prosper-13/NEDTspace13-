package com.facedetection;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import org.opencv.objdetect.CascadeClassifier;
import org.opencv.face.FaceRecognizer;
import org.opencv.face.LBPHFaceRecognizer;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class MainController {
    @FXML
    private Label statusLabel;
    @FXML
    private Pane videoPane;
    @FXML
    private Button startButton;
    @FXML
    private Button stopButton;
    @FXML
    private VBox registrationForm;
    @FXML
    private TextField nameField;
    @FXML
    private TextField emailField;
    @FXML
    private PasswordField passwordField;
    @FXML
    private Button registerButton;

    private VideoCapture capture;
    private ImageView imageView;
    private boolean isRunning = false;
    private CascadeClassifier faceDetector;
    private int detectedFaces = 0;
    private FaceRecognizer faceRecognizer;
    private List<Mat> trainingImages;
    private List<Integer> trainingLabels;
    private Connection databaseConnection;
    private int currentUserId = -1;

    // Database configuration
    private static final String DB_URL = "jdbc:sqlserver://TESLA\\SQLEXPRESS;databaseName=FaceDetectionDB;integratedSecurity=true;encrypt=true;trustServerCertificate=true;";
    // Replace DESKTOP-ABC123\SQLEXPRESS with your actual SQL Server instance name
    // You can find your instance name in SQL Server Configuration Manager or by running:
    // SELECT @@SERVERNAME in SQL Server Management Studio

    private static final int FACE_CAPTURE_COUNT = 5; // Number of face samples to capture
    private int currentCaptureCount = 0;
    private boolean isCapturingFace = false;

    @FXML
    public void initialize() {
        try {
            // Load OpenCV native library
            nu.pattern.OpenCV.loadShared();
            System.loadLibrary(org.opencv.core.Core.NATIVE_LIBRARY_NAME);

            // Initialize face detector
            InputStream cascadeStream = getClass().getResourceAsStream("/haarcascade_frontalface_alt.xml");
            if (cascadeStream == null) {
                throw new IOException("Could not find cascade classifier file");
            }

            Path tempFile = Files.createTempFile("haarcascade_frontalface_alt", ".xml");
            Files.copy(cascadeStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            faceDetector = new CascadeClassifier(tempFile.toString());
            
            if (faceDetector.empty()) {
                throw new IOException("Failed to load cascade classifier");
            }

            // Initialize face recognizer
            faceRecognizer = LBPHFaceRecognizer.create();
            trainingImages = new ArrayList<>();
            trainingLabels = new ArrayList<>();

            // Initialize database connection
            initializeDatabase();

            // Initialize image view
            imageView = new ImageView();
            imageView.setFitWidth(600);
            imageView.setFitHeight(400);
            imageView.setPreserveRatio(true);
            videoPane.getChildren().add(imageView);

            // Apply animations
            applyAnimations();

            updateStatus("Ready to start camera");
        } catch (Exception e) {
            e.printStackTrace();
            updateStatus("Error initializing: " + e.getMessage());
        }
    }

    private void applyAnimations() {
        // Apply fade-in animation to the registration form
        FadeTransition fadeIn = new FadeTransition(Duration.seconds(1.5), registrationForm);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.play();

        // Add scale animation to register button
        ScaleTransition scaleTransition = new ScaleTransition(Duration.millis(200), registerButton);
        registerButton.setOnMouseEntered(e -> {
            scaleTransition.setToX(1.05);
            scaleTransition.setToY(1.05);
            scaleTransition.playFromStart();
        });
        registerButton.setOnMouseExited(e -> {
            scaleTransition.setToX(1.0);
            scaleTransition.setToY(1.0);
            scaleTransition.playFromStart();
        });

        // Add pulse animation to start button
        Timeline pulseAnimation = new Timeline(
            new KeyFrame(Duration.ZERO, new KeyValue(startButton.scaleXProperty(), 1.0)),
            new KeyFrame(Duration.ZERO, new KeyValue(startButton.scaleYProperty(), 1.0)),
            new KeyFrame(Duration.seconds(1), new KeyValue(startButton.scaleXProperty(), 1.05)),
            new KeyFrame(Duration.seconds(1), new KeyValue(startButton.scaleYProperty(), 1.05)),
            new KeyFrame(Duration.seconds(2), new KeyValue(startButton.scaleXProperty(), 1.0)),
            new KeyFrame(Duration.seconds(2), new KeyValue(startButton.scaleYProperty(), 1.0))
        );
        pulseAnimation.setCycleCount(Timeline.INDEFINITE);
        pulseAnimation.play();
    }

    private void initializeDatabase() {
        try {
            // Create SQL Server database connection with Windows Authentication
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            databaseConnection = DriverManager.getConnection(DB_URL);
            
            // Create users table if it doesn't exist
            String createTableSQL = "IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'users' AND schema_id = SCHEMA_ID('dbo')) " +
                    "CREATE TABLE users (" +
                    "id INT IDENTITY(1,1) PRIMARY KEY," +
                    "name NVARCHAR(100) NOT NULL," +
                    "email NVARCHAR(100) UNIQUE NOT NULL," +
                    "password NVARCHAR(255) NOT NULL," +
                    "face_data VARBINARY(MAX))";
            
            try (Statement stmt = databaseConnection.createStatement()) {
                stmt.execute(createTableSQL);
            }
        } catch (Exception e) {
            e.printStackTrace();
            updateStatus("Database error: " + e.getMessage());
        }
    }

    @FXML
    protected void handleRegister() {
        String name = nameField.getText();
        String email = emailField.getText();
        String password = passwordField.getText();

        if (name.isEmpty() || email.isEmpty() || password.isEmpty()) {
            showAlert("Error", "Please fill in all fields");
            return;
        }

        try {
            // Check if email already exists
            if (isEmailExists(email)) {
                showAlert("Error", "Email already registered");
                return;
            }

            // Insert user into database
            String insertSQL = "INSERT INTO users (name, email, password) VALUES (?, ?, ?); SELECT SCOPE_IDENTITY();";
            try (PreparedStatement pstmt = databaseConnection.prepareStatement(insertSQL)) {
                pstmt.setString(1, name);
                pstmt.setString(2, email);
                pstmt.setString(3, password); // In a real app, hash the password
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        currentUserId = rs.getInt(1);
                    }
                }
            }

            // Animate the registration form disappearing
            FadeTransition fadeOut = new FadeTransition(Duration.seconds(1), registrationForm);
            fadeOut.setFromValue(1.0);
            fadeOut.setToValue(0.0);
            fadeOut.setOnFinished(event -> {
                registrationForm.setVisible(false);
                startButton.setDisable(false);
                
                // Bounce effect for the Start Camera button
                TranslateTransition bounce = new TranslateTransition(Duration.millis(100), startButton);
                bounce.setFromY(0);
                bounce.setToY(-10);
                bounce.setCycleCount(6);
                bounce.setAutoReverse(true);
                bounce.play();
            });
            fadeOut.play();
            
            updateStatus("Registration successful! Please look at the camera to capture your face.");
            isCapturingFace = true;
            currentCaptureCount = 0;
        } catch (SQLException e) {
            e.printStackTrace();
            showAlert("Error", "Registration failed: " + e.getMessage());
        }
    }

    private boolean isEmailExists(String email) throws SQLException {
        String sql = "SELECT COUNT(*) FROM users WHERE email = ?";
        try (PreparedStatement pstmt = databaseConnection.prepareStatement(sql)) {
            pstmt.setString(1, email);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        }
        return false;
    }

    private int getUserIdByEmail(String email) throws SQLException {
        String sql = "SELECT id FROM users WHERE email = ?";
        try (PreparedStatement pstmt = databaseConnection.prepareStatement(sql)) {
            pstmt.setString(1, email);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("id");
            }
        }
        return -1;
    }

    @FXML
    protected void handleStartCamera() {
        if (!isRunning) {
            capture = new VideoCapture(0);
            if (capture.isOpened()) {
                isRunning = true;
                startButton.setDisable(true);
                stopButton.setDisable(false);
                updateStatus("Camera started - Detecting faces...");
                startCaptureThread();
            } else {
                updateStatus("Error: Could not open camera");
            }
        }
    }

    @FXML
    protected void handleStopCamera() {
        if (isRunning) {
            isRunning = false;
            if (capture != null) {
                capture.release();
            }
            startButton.setDisable(false);
            stopButton.setDisable(true);
            updateStatus("Camera stopped - " + detectedFaces + " faces detected");
            detectedFaces = 0;
        }
    }

    private void startCaptureThread() {
        Thread captureThread = new Thread(() -> {
            Mat frame = new Mat();
            while (isRunning) {
                if (capture.read(frame)) {
                    // Detect faces
                    MatOfRect faces = new MatOfRect();
                    faceDetector.detectMultiScale(frame, faces);

                    // Update face count
                    int currentFaces = faces.toArray().length;
                    if (currentFaces != detectedFaces) {
                        detectedFaces = currentFaces;
                        Platform.runLater(() -> updateStatus("Camera active - " + detectedFaces + " faces detected"));
                    }

                    // Process each detected face
                    for (Rect face : faces.toArray()) {
                        Mat faceROI = new Mat(frame, face);
                        Mat resizedFace = new Mat();
                        Size size = new Size(100, 100);
                        Imgproc.resize(faceROI, resizedFace, size);

                        if (isCapturingFace && currentUserId != -1) {
                            // Training mode - save face data
                            if (currentCaptureCount < FACE_CAPTURE_COUNT) {
                                trainingImages.add(resizedFace);
                                trainingLabels.add(currentUserId);
                                currentCaptureCount++;
                                
                                // Save face data to database
                                saveFaceDataToDatabase(resizedFace);
                                
                                Platform.runLater(() -> 
                                    updateStatus("Capturing face " + currentCaptureCount + " of " + FACE_CAPTURE_COUNT));
                                
                                if (currentCaptureCount >= FACE_CAPTURE_COUNT) {
                                    isCapturingFace = false;
                                    faceRecognizer.train(trainingImages, new MatOfInt(trainingLabels.toArray(new Integer[0])));
                                    Platform.runLater(() -> updateStatus("Face registration complete!"));
                                }
                            }
                        } else {
                            // Recognition mode - try to identify the face
                            try {
                                int[] label = new int[1];
                                double[] confidence = new double[1];
                                faceRecognizer.predict(resizedFace, label, confidence);
                                
                                if (confidence[0] < 50) { // Threshold for recognition
                                    String userName = getUserNameById(label[0]);
                                    updateLastLogin(label[0]);
                                    Platform.runLater(() -> updateStatus("Welcome back, " + userName + "!"));
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }

                        // Draw rectangle around face
                        Imgproc.rectangle(frame, face, new Scalar(255, 0, 0), 2);
                    }

                    // Convert Mat to Image and display
                    Image image = mat2Image(frame);
                    Platform.runLater(() -> imageView.setImage(image));
                }
            }
        });
        captureThread.setDaemon(true);
        captureThread.start();
    }

    private void saveFaceDataToDatabase(Mat faceData) {
        try {
            MatOfByte mob = new MatOfByte();
            org.opencv.imgcodecs.Imgcodecs.imencode(".png", faceData, mob);
            byte[] faceBytes = mob.toArray();

            String updateSQL = "UPDATE users SET face_data = ? WHERE id = ?";
            try (PreparedStatement pstmt = databaseConnection.prepareStatement(updateSQL)) {
                pstmt.setBytes(1, faceBytes);
                pstmt.setInt(2, currentUserId);
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
            updateStatus("Error saving face data: " + e.getMessage());
        }
    }

    private String getUserNameById(int userId) throws SQLException {
        String sql = "SELECT name FROM users WHERE id = ?";
        try (PreparedStatement pstmt = databaseConnection.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getString("name");
            }
        }
        return "Unknown User";
    }

    private void updateLastLogin(int userId) {
        try {
            String sql = "UPDATE users SET last_login = GETDATE() WHERE id = ?";
            try (PreparedStatement pstmt = databaseConnection.prepareStatement(sql)) {
                pstmt.setInt(1, userId);
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void showAlert(String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }

    private void updateStatus(String message) {
        Platform.runLater(() -> {
            // Create fade transition for smooth status updates
            FadeTransition fade = new FadeTransition(Duration.millis(150), statusLabel);
            fade.setFromValue(1.0);
            fade.setToValue(0.3);
            fade.setOnFinished(event -> {
                statusLabel.setText(message);
                FadeTransition fadeIn = new FadeTransition(Duration.millis(150), statusLabel);
                fadeIn.setFromValue(0.3);
                fadeIn.setToValue(1.0);
                fadeIn.play();
            });
            fade.play();
        });
    }

    private Image mat2Image(Mat frame) {
        MatOfByte buffer = new MatOfByte();
        org.opencv.imgcodecs.Imgcodecs.imencode(".png", frame, buffer);
        return new Image(new java.io.ByteArrayInputStream(buffer.toArray()));
    }
} 