import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './styles/tokens/variables.css'
import './styles/base.css'
import './styles/components.css'

const app = createApp(App)
app.use(createPinia()).use(router)

await router.isReady()
app.mount('#app')
