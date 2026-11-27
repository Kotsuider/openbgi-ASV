.engine 1.69/472
push 2
push 1
push 0
sys.set_display_mode
push 1
sys.show_window
push 1
gfx0.display_enable
push 128
gfx0.movie_volume
push_code_addr mpeg
push 0
push 0
push 800
push 600
gfx0.movie_play
push 7000
push 0
push 0
sys.wait_time_key
gfx0.movie_stop
push_code_addr mp4
push 0
push 0
push 800
push 600
gfx0.movie_play
push 12000
push 0
push 0
sys.wait_time_key
gfx0.movie_stop
sys.quit
mpeg:
.str "opening.mpg"
mp4:
.str "data00500.arc"
