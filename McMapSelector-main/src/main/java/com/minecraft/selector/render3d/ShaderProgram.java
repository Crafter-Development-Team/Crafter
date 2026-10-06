package com.minecraft.selector.render3d;

import org.lwjgl.opengl.GL20;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;

/**
 * 着色器程序管理
 */
public class ShaderProgram {
    private int programId;
    
    public ShaderProgram(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(vertexSource, GL20.GL_VERTEX_SHADER);
        int fragmentShader = compileShader(fragmentSource, GL20.GL_FRAGMENT_SHADER);
        
        programId = GL20.glCreateProgram();
        GL20.glAttachShader(programId, vertexShader);
        GL20.glAttachShader(programId, fragmentShader);
        GL20.glLinkProgram(programId);
        
        if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == 0) {
            System.err.println("着色器链接失败: " + GL20.glGetProgramInfoLog(programId));
        }
        
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
    }
    
    private int compileShader(String source, int type) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            System.err.println("着色器编译失败: " + GL20.glGetShaderInfoLog(shader));
        }
        
        return shader;
    }
    
    public void use() {
        GL20.glUseProgram(programId);
    }
    
    public void setMatrix4f(String name, Matrix4f matrix) {
        int location = GL20.glGetUniformLocation(programId, name);
        FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
        matrix.get(buffer);
        GL20.glUniformMatrix4fv(location, false, buffer);
    }
    
    public void setVector3f(String name, Vector3f vector) {
        int location = GL20.glGetUniformLocation(programId, name);
        GL20.glUniform3f(location, vector.x, vector.y, vector.z);
    }
    
    public void setFloat(String name, float value) {
        int location = GL20.glGetUniformLocation(programId, name);
        GL20.glUniform1f(location, value);
    }
    
    public void setInt(String name, int value) {
        int location = GL20.glGetUniformLocation(programId, name);
        GL20.glUniform1i(location, value);
    }
    
    public void delete() {
        GL20.glDeleteProgram(programId);
    }
}
