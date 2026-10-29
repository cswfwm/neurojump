package com.neurojump;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;

/** 游戏入口：创建后进入主菜单。各界面自行管理相机，统一使用 1280x720 坐标。 */
public class GameRoot extends Game {
    @Override
    public void create() {
        Gdx.input.setCatchKey(Input.Keys.BACK, true);
        setScreen(new MenuScreen(this));
    }
}
