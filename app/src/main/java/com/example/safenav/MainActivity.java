package com.example.safenav;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button open = findViewById(R.id.openCameraBtn);
        open.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, CameraActivity.class)));

        Button exit = findViewById(R.id.exitBtn); // NOTE: matches XML id exactly
        exit.setOnClickListener(v -> {
            moveTaskToBack(true);  // background the task
            finishAffinity();      // close all activities in this task
        });
    }
}
