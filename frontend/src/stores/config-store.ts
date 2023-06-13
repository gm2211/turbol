import {defineStore} from 'pinia'
import axios from 'axios'

interface FrontendConfig {}

interface ConfigState {
    config: FrontendConfig | undefined
}

export const useConfigStore = defineStore('config', {
    state: (): ConfigState => {
        return {
            config: undefined
        }
    },
    getters: {},
    actions: {
        async fetchConfig() {
            if (this.config) {
                return
            }
            await axios
                .get(`/api/config`)
                .then((axiosResponse) => {
                    this.config = axiosResponse.data
                })
                .catch((error) => {
                    console.log(`Error loading config: ${error}`)
                })
        }
    }
})
