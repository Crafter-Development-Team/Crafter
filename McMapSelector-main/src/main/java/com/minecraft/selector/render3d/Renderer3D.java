package com.minecraft.selector.render3d;

import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import org.joml.Matrix4f;
import java.nio.IntBuffer;

/**
 * 3D 渲染器，使用 LWJGL 和 OpenGL
 */
public class Renderer3D {
    private long window;
    private int width = 1200;
    private int height = 800;
    private String title = "Minecraft Map Selector - 3D";
    
    private Camera camera;
    private ShaderProgram shaderProgram;
    private BlockMesh blockMesh;
    
    private boolean[] keys = new boolean[512];
    private double lastMouseX = 0;
    private double lastMouseY = 0;
    private boolean firstMouse = true;
    
    private RenderCallback renderCallback;
    
    public interface RenderCallback {
        void onUpdate(Renderer3D renderer);
        void onRender(Renderer3D renderer);
    }
    
    public Renderer3D() {
        if (!GLFW.glfwInit()) {
            throw new RuntimeException("GLFW 初始化失败");
        }
        
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        
        window = GLFW.glfwCreateWindow(width, height, title, 0, 0);
        if (window == 0) {
            throw new RuntimeException("创建 GLFW 窗口失败");
        }
        
        GLFW.glfwMakeContextCurrent(window);
        GLFW.glfwSwapInterval(1);
        
        GL.createCapabilities();
        
        setupCallbacks();
        initializeRenderer();
    }
    
    private void setupCallbacks() {
        GLFW.glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            if (action == GLFW.GLFW_PRESS) {
                keys[key] = true;
            } else if (action == GLFW.GLFW_RELEASE) {
                keys[key] = false;
            }
            
            if (key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS) {
                GLFW.glfwSetWindowShouldClose(w, true);
            }
        });
        
        GLFW.glfwSetCursorPosCallback(window, (w, xpos, ypos) -> {
            if (firstMouse) {
                lastMouseX = xpos;
                lastMouseY = ypos;
                firstMouse = false;
            }
            
            double xOffset = xpos - lastMouseX;
            double yOffset = lastMouseY - ypos;
            
            lastMouseX = xpos;
            lastMouseY = ypos;
            
            camera.rotate((float) xOffset, (float) yOffset);
        });
        
        GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
    }
    
    private void initializeRenderer() {
        camera = new Camera(0, 64, 0);
        
        String vertexShader = "#version 150 core\n" +
            "in vec3 position;\n" +
            "in vec3 color;\n" +
            "out vec3 vertexColor;\n" +
            "uniform mat4 projection;\n" +
            "uniform mat4 view;\n" +
            "uniform mat4 model;\n" +
            "void main() {\n" +
            "    gl_Position = projection * view * model * vec4(position, 1.0);\n" +
            "    vertexColor = color;\n" +
            "}\n";
        
        String fragmentShader = "#version 150 core\n" +
            "in vec3 vertexColor;\n" +
            "out vec4 FragColor;\n" +
            "void main() {\n" +
            "    FragColor = vec4(vertexColor, 1.0);\n" +
            "}\n";
        
        shaderProgram = new ShaderProgram(vertexShader, fragmentShader);
        blockMesh = new BlockMesh();
        
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
    }
    
    /**
     * 运行渲染循环
     */
    public void run(RenderCallback callback) {
        this.renderCallback = callback;
        
        while (!GLFW.glfwWindowShouldClose(window)) {
            update();
            render();
            
            GLFW.glfwSwapBuffers(window);
            GLFW.glfwPollEvents();
        }
        
        cleanup();
    }
    
    private void update() {
        // 处理键盘输入
        if (keys[GLFW.GLFW_KEY_W]) {
            camera.move(Camera.CameraMovement.FORWARD);
        }
        if (keys[GLFW.GLFW_KEY_S]) {
            camera.move(Camera.CameraMovement.BACKWARD);
        }
        if (keys[GLFW.GLFW_KEY_A]) {
            camera.move(Camera.CameraMovement.LEFT);
        }
        if (keys[GLFW.GLFW_KEY_D]) {
            camera.move(Camera.CameraMovement.RIGHT);
        }
        if (keys[GLFW.GLFW_KEY_SPACE]) {
            camera.move(Camera.CameraMovement.UP);
        }
        if (keys[GLFW.GLFW_KEY_LEFT_SHIFT]) {
            camera.move(Camera.CameraMovement.DOWN);
        }
        
        if (renderCallback != null) {
            renderCallback.onUpdate(this);
        }
    }
    
    private void render() {
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        
        shaderProgram.use();
        
        // 设置矩阵
        Matrix4f projection = camera.getProjectionMatrix((float) width / height);
        Matrix4f view = camera.getViewMatrix();
        Matrix4f model = new Matrix4f();
        
        shaderProgram.setMatrix4f("projection", projection);
        shaderProgram.setMatrix4f("view", view);
        shaderProgram.setMatrix4f("model", model);
        
        if (renderCallback != null) {
            renderCallback.onRender(this);
        }
        
        blockMesh.render();
    }
    
    private void cleanup() {
        blockMesh.delete();
        shaderProgram.delete();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }
    
    public Camera getCamera() {
        return camera;
    }
    
    public BlockMesh getBlockMesh() {
        return blockMesh;
    }
    
    public void setWindowSize(int width, int height) {
        this.width = width;
        this.height = height;
    }
    
    public int getWidth() {
        return width;
    }
    
    public int getHeight() {
        return height;
    }
    
    public long getWindow() {
        return window;
    }
}
