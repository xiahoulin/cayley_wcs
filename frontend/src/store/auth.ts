import { reactive } from 'vue'

const TOKEN_KEY = 'cayleywcs_token'
const USER_KEY = 'cayleywcs_user'

export const auth = reactive({
  token: localStorage.getItem(TOKEN_KEY) || '',
  userName: localStorage.getItem(USER_KEY) || '',

  get isAuthed(): boolean {
    return !!this.token
  },

  setSession(token: string, userName: string) {
    this.token = token
    this.userName = userName
    localStorage.setItem(TOKEN_KEY, token)
    localStorage.setItem(USER_KEY, userName)
  },

  logout() {
    this.token = ''
    this.userName = ''
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
    if (location.hash !== '#/login') {
      location.hash = '#/login'
    }
  },
})
