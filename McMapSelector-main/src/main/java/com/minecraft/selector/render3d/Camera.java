package com.minecraft.selector.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 3D 相机类，实现 Minecraft 风格的第一人称视角
 */
public class Camera {
    private Vector3f position;
    private Vector3f front;
    private Vector3f up;
    private Vector3f right;
    
    private float yaw = -90.0f;
    private float pitch = 0.0f;
    private float fov = 45.0f;
    
    private static final float SPEED = 0.05f;
    private static final float SENSITIVITY = 0.1f;
    
    public Camera(float x, float y, float z) {
        this.position = new Vector3f(x, y, z);
        this.front = new Vector3f(0.0f, 0.0f, -1.0f);
        this.up = new Vector3f(0.0f, 1.0f, 0.0f);
        this.right = new Vector3f(1.0f, 0.0f, 0.0f);
        updateCameraVectors();
    }
    
    /**
     * 获取视图矩阵
     */
    public Matrix4f getViewMatrix() {
        Matrix4f view = new Matrix4f();
        Vector3f center = new Vector3f(position).add(front);
        view.lookAt(position, center, up);
        return view;
    }
    
    /**
     * 获取投影矩阵
     */
    public Matrix4f getProjectionMatrix(float aspect) {
        Matrix4f projection = new Matrix4f();
        projection.perspective((float) Math.toRadians(fov), aspect, 0.1f, 1000.0f);
        return projection;
    }
    
    /**
     * 移动相机
     */
    public void move(CameraMovement direction) {
        float velocity = SPEED;
        switch (direction) {
            case FORWARD:
                position.add(new Vector3f(front).mul(velocity));
                break;
            case BACKWARD:
                position.sub(new Vector3f(front).mul(velocity));
                break;
            case LEFT:
                position.sub(new Vector3f(right).mul(velocity));
                break;
            case RIGHT:
                position.add(new Vector3f(right).mul(velocity));
                break;
            case UP:
                position.add(new Vector3f(up).mul(velocity));
                break;
            case DOWN:
                position.sub(new Vector3f(up).mul(velocity));
                break;
        }
    }
    
    /**
     * 旋转相机
     */
    public void rotate(float xOffset, float yOffset) {
        xOffset *= SENSITIVITY;
        yOffset *= SENSITIVITY;
        
        yaw += xOffset;
        pitch -= yOffset;
        
        // 限制俯仰角
        if (pitch > 89.0f) pitch = 89.0f;
        if (pitch < -89.0f) pitch = -89.0f;
        
        updateCameraVectors();
    }
    
    /**
     * 更新相机向量
     */
    private void updateCameraVectors() {
        Vector3f front = new Vector3f();
        front.x = (float) (Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)));
        front.y = (float) Math.sin(Math.toRadians(pitch));
        front.z = (float) (Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)));
        this.front = front.normalize();
        
        this.right = new Vector3f(this.front).cross(new Vector3f(0.0f, 1.0f, 0.0f)).normalize();
        this.up = new Vector3f(this.right).cross(this.front).normalize();
    }
    
    /**
     * 设置相机位置
     */
    public void setPosition(float x, float y, float z) {
        this.position = new Vector3f(x, y, z);
    }
    
    /**
     * 获取相机位置
     */
    public Vector3f getPosition() {
        return new Vector3f(position);
    }
    
    /**
     * 设置 FOV
     */
    public void setFOV(float fov) {
        this.fov = Math.max(1.0f, Math.min(120.0f, fov));
    }
    
    /**
     * 相机移动方向枚举
     */
    public enum CameraMovement {
        FORWARD, BACKWARD, LEFT, RIGHT, UP, DOWN
    }
}
